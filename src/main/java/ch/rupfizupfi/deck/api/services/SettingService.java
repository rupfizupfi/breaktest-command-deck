package ch.rupfizupfi.deck.api.services;

import ch.rupfizupfi.deck.data.Setting;
import ch.rupfizupfi.deck.data.SettingRepository;
import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.Nonnull;
import com.vaadin.hilla.Nullable;
import com.vaadin.hilla.crud.CrudService;
import com.vaadin.hilla.crud.filter.Filter;
import jakarta.annotation.security.PermitAll;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.util.List;

@BrowserCallable
@PermitAll
public class SettingService implements CrudService<Setting<?>, String> {
    private final SettingRepository repository;

    SettingService(SettingRepository service) {
        this.repository = service;
    }

    public Setting<?> getSetting(String key) throws IOException {
        return repository.getSetting(key);
    }

    public @Nonnull List<@Nonnull Setting<?>> sync() {
        return repository.syncAndGetSettings();
    }

    @Override
    public @Nullable Setting<?> save(Setting<?> value) {
        try {
            repository.saveSetting(value);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return value;
    }

    @Override
    public void delete(String s) {
        try {
            repository.deleteSetting(s);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public @Nonnull List<@Nonnull Setting<?>> list(Pageable pageable, @Nullable Filter filter) {
        return repository.getAllSettings();
    }
}