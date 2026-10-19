package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.SettingRepository;
import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestParameterRepository;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryServiceForOwnerData;
import ch.rupfizupfi.deck.rig.RigLimits;
import ch.rupfizupfi.deck.rig.TestParameterRules;
import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.exception.EndpointException;
import jakarta.annotation.security.PermitAll;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

@BrowserCallable
@PermitAll
public class TestParameterService extends CrudRepositoryServiceForOwnerData<TestParameter, TestParameterRepository> {
    private final SettingRepository settings;

    TestParameterService(SettingRepository settings) {
        this.settings = settings;
    }

    /**
     * Refuses an executable parameter the bench could not run, against the rig limits as they stand
     * now; the deck checks the same {@link TestParameterRules} again at run start. An
     * {@link EndpointException} because only its message reaches the browser's error toast.
     * {@code saveAll} goes through here too.
     */
    @Override
    @Nullable
    public TestParameter save(@NonNull TestParameter value) {
        try {
            TestParameterRules.requireSavable(value, RigLimits.read(settings));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new EndpointException("Cannot save the test parameter: " + e.getMessage());
        }
        return super.save(value);
    }
}
