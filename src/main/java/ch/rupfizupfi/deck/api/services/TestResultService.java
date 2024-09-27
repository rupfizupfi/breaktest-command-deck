package ch.rupfizupfi.deck.api.services;


import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import ch.rupfizupfi.deck.security.CheckUserCanOnlyAccessOwnData;
import com.vaadin.hilla.BrowserCallable;
import ch.rupfizupfi.deck.hilla.crud.CrudRepositoryService;
import jakarta.annotation.security.PermitAll;

import java.nio.file.Paths;
import java.util.Arrays;

@BrowserCallable
@PermitAll
@CheckUserCanOnlyAccessOwnData
public class TestResultService extends CrudRepositoryService<TestResult, TestResultRepository> {
    public String[] listCSVResults(long id) {
        String[] paths = new CSVStoreService().listCSVFilesForTestResult(id);
        return Arrays.stream(paths).map(path -> Paths.get(path).getFileName().toString()).toArray(String[]::new);
    }

    public String readCSVData(long id, String fileName) {
        return new CSVStoreService().readCSVDataForTestResult(id, fileName);
    }
}
