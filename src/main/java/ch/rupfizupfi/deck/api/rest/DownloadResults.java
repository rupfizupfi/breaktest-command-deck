package ch.rupfizupfi.deck.api.rest;

import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/DownloadEndpoint")
@AnonymousAllowed
public class DownloadResults {
    protected TestResultRepository testResultRepository;
    protected CSVStoreService csvStoreService;

    public DownloadResults(TestResultRepository testResultRepository) {
        this.testResultRepository = testResultRepository;
        this.csvStoreService = new CSVStoreService();
    }

    @RequestMapping(value = "/get", method = RequestMethod.GET)
    public String get() {
        StringBuilder result = new StringBuilder();
        testResultRepository.findAll().forEach(testResult -> {
            // create array of field from testResult, name, result:
            var fields = new String[] {
                    testResult.sample.name,
                    testResult.testParameter.type,
                    getPeaksFromResultFiles(testResult.getId()),
                    testResult.description,
                    testResult.resultText
            };
            result.append('"').append(String.join("\",\"", fields)).append("\"\n");
        });

        return result.toString();
    }

    protected String getPeaksFromResultFiles(long id){
        String[] paths = this.csvStoreService.listCSVFilesForTestResult(id);
        var results = Arrays.stream(paths).map(path -> getPeakFromResultFile(id, path))
                .filter(Objects::nonNull)
                .toArray(String[]::new);

        return String.join(",", results);
    }

    protected String getPeakFromResultFile(long id, String path){
        Path file = Paths.get(path);
        String data = this.csvStoreService.readCSVDataForTestResult(id, file.getFileName().toString());

        List<String> lines = Arrays.asList(data.split("\n"));
        if (lines.size() < 100) {
            return null;
        }

        List<String[]> csv = lines.stream().map(line -> line.split(",")).toList();
        return csv.stream().map(line -> line[line.length - 1]).max(String::compareTo).orElse("");
    }
}