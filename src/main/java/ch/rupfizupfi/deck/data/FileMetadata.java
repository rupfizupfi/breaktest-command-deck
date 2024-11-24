package ch.rupfizupfi.deck.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.jetbrains.annotations.Nullable;

@Entity
@Table(name = "file_metadata")
public class FileMetadata extends AbstractEntity {

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_path", nullable = true)
    private String filePath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private TestResult testResult;

    public FileMetadata() {
    }

    public FileMetadata(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public @Nullable TestResult getTestResult() {
        return testResult;
    }

    public void setTestResult(TestResult testResult) {
        this.testResult = testResult;
    }
}