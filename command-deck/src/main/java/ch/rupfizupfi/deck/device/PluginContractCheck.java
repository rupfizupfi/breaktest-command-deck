package ch.rupfizupfi.deck.device;

import ch.rupfizupfi.deck.device.api.ContractVersion;
import ch.rupfizupfi.deck.device.api.DeviceApi;
import ch.rupfizupfi.deck.device.api.DriveProvider;
import ch.rupfizupfi.deck.device.api.LoadCellStreamProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses a hardware provider whose driver plugin never checked itself against the contract.
 * <p>
 * {@link DeviceApi#verifyPluginBuiltAgainst} lives in the plugin, so a jar that never calls it - one
 * built before the check existed - would otherwise load unchecked. A passing call is recorded under
 * the calling class; a provider is vouched for when that class is the provider's own, one enclosing
 * it (Kotlin's anonymous {@code object :} providers), or the one whose {@code @Bean} method created
 * it. The recorded version also names the {@link ContractFeature}s the jar predates, each logged as
 * a warning.
 * <p>
 * Real mode only: the simulated providers are deck code and never report. A
 * {@code BeanPostProcessor} because the plugin's call runs when its auto-configuration is
 * instantiated, after {@link HardwareModeCheck} has already run. It inspects the bean and its
 * definition only, never opens a device.
 */
@Component
public class PluginContractCheck implements BeanPostProcessor, BeanFactoryAware, EnvironmentAware, PriorityOrdered {

    private static final Logger logger = LoggerFactory.getLogger(PluginContractCheck.class);

    private static final List<Class<?>> PROVIDER_TYPES = List.of(DriveProvider.class, LoadCellStreamProvider.class);

    // Aware callbacks rather than constructor injection: a PriorityOrdered post-processor is created
    // before the one that resolves autowired constructors.
    private Environment environment;
    private ConfigurableListableBeanFactory beanFactory;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof ConfigurableListableBeanFactory listable) {
            this.beanFactory = listable;
        }
    }

    /**
     * Last among the {@link PriorityOrdered} post-processors. Implementing that interface, not this
     * value, is what registers the check before any provider is created.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        List<Class<?>> providerTypes = PROVIDER_TYPES.stream().filter(type -> type.isInstance(bean)).toList();
        if (providerTypes.isEmpty() || !isRealMode() || HardwareModeCheck.isBuildTimeContext()) {
            return bean;
        }

        Class<?> providerClass = ClassUtils.getUserClass(bean);
        String builtAgainst = reportedVersion(responsibleClasses(providerClass, beanName));
        if (builtAgainst == null) {
            throw new IllegalStateException(neverReported(providerTypes.getFirst(), beanName, providerClass));
        }
        for (Class<?> providerType : providerTypes) {
            for (ContractFeature feature : ContractFeature.missingFrom(providerType, builtAgainst)) {
                logger.warn("{} bean '{}' ({}) was built against device-api {}, older than {}, which added {}: {}."
                                + " Rebuild the driver and copy the jar into drivers/ (doc/dev/backend/driver-jars.md).",
                        providerType.getSimpleName(), beanName, providerClass.getName(), builtAgainst,
                        feature.since(), feature.method(), feature.consequence());
            }
        }
        return bean;
    }

    private boolean isRealMode() {
        String configured = environment.getProperty(HardwareMode.PROPERTY, HardwareMode.DEFAULT.propertyValue());
        return HardwareMode.parse(configured) == HardwareMode.REAL;
    }

    /**
     * The provider class, its enclosing classes, and the class declaring its {@code @Bean} method.
     * {@code getUserClass} strips a CGLIB subclass, though the call itself already runs in the user
     * class's constructor.
     */
    private Set<Class<?>> responsibleClasses(Class<?> providerClass, String beanName) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (Class<?> type = providerClass; type != null; type = type.getEnclosingClass()) {
            classes.add(type);
        }
        if (beanFactory != null && beanFactory.containsBeanDefinition(beanName)) {
            BeanDefinition definition = beanFactory.getMergedBeanDefinition(beanName);
            if (definition instanceof RootBeanDefinition root && root.getResolvedFactoryMethod() != null) {
                classes.add(ClassUtils.getUserClass(root.getResolvedFactoryMethod().getDeclaringClass()));
            } else if (definition.getFactoryBeanName() != null) {
                Class<?> factory = beanFactory.getType(definition.getFactoryBeanName());
                if (factory != null) {
                    classes.add(ClassUtils.getUserClass(factory));
                }
            }
        }
        return classes;
    }

    private static String reportedVersion(Set<Class<?>> responsible) {
        Map<Class<?>, String> reported = DeviceApi.reportedBuiltAgainst();
        return responsible.stream().map(reported::get).filter(version -> version != null).findFirst().orElse(null);
    }

    private static String neverReported(Class<?> providerType, String beanName, Class<?> providerClass) {
        CodeSource source = providerClass.getProtectionDomain().getCodeSource();
        String origin = source == null || source.getLocation() == null ? "" : ", loaded from " + source.getLocation();
        return HardwareModeCheck.message(
                HardwareMode.PROPERTY + "=real, but the " + providerType.getSimpleName() + " bean '" + beanName
                        + "' (" + providerClass.getName() + origin + ") comes from a driver plugin that never "
                        + "checked itself against device-api " + ContractVersion.VALUE + ".",
                "Every driver plugin must call DeviceApi.verifyPluginBuiltAgainst(ContractVersion.VALUE) "
                        + "from the auto-configuration that declares the provider bean, or from the provider "
                        + "class itself; a jar that does not was most likely built before the check existed, "
                        + "and nothing tells whether it fits this contract. Rebuild the driver from its "
                        + "current source (`./gradlew shadowJar` in ../driver/dscusb or ../driver/usbmodbus), "
                        + "copy the jar into drivers/ and restart - no application rebuild is needed. "
                        + "`gradlew :command-deck:driverPluginTest` checks a jar before it is deployed; see "
                        + "doc/dev/backend/driver-jars.md.");
    }
}
