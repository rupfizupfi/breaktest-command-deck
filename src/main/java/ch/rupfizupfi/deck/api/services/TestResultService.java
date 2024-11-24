package ch.rupfizupfi.deck.api.services;


import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryServiceForOwnerData;
import com.vaadin.hilla.BrowserCallable;
import jakarta.annotation.security.PermitAll;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Paths;
import java.util.Arrays;

@BrowserCallable
@PermitAll
public class TestResultService extends CrudRepositoryServiceForOwnerData<TestResult, TestResultRepository> {
    @Autowired
    private CSVStoreService csvStoreService;

    public String[] listCSVResults(long id) {
        String[] paths = csvStoreService.listCSVFilesForTestResult(id);
        return Arrays.stream(paths).map(path -> Paths.get(path).getFileName().toString()).toArray(String[]::new);
    }

    public String readCSVData(long id, String fileName) {
        return csvStoreService.readCSVDataForTestResult(id, fileName);
    }
}
