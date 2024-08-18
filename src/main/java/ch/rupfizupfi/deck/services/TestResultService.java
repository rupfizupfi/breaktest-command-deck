package ch.rupfizupfi.deck.services;


import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import com.vaadin.hilla.BrowserCallable;
import com.vaadin.hilla.crud.CrudRepositoryService;
import jakarta.annotation.security.PermitAll;

import java.nio.file.Paths;
import java.util.Arrays;

@BrowserCallable
@PermitAll
public class TestResultService extends CrudRepositoryService<TestResult, Long, TestResultRepository> {
    public String[] listCSVResults(long id) {
        String[] paths = new CSVStoreService().listCSVFilesForTestResult(id);
        return Arrays.stream(paths).map(path -> Paths.get(path).getFileName().toString()).toArray(String[]::new);
    }

    public String readCSVData(long id, String fileName) {
        return new CSVStoreService().readCSVDataForTestResult(id, fileName);
    }
}
