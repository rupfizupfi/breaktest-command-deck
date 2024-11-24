package ch.rupfizupfi.deck.api.rest;

import ch.rupfizupfi.deck.data.Project;
import ch.rupfizupfi.deck.data.ProjectRepository;
import ch.rupfizupfi.deck.data.SampleRepository;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.filesystem.CSVStoreService;
import jakarta.annotation.security.PermitAll;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/DownloadEndpoint")
@PermitAll
public class DownloadResults {
    private static final Logger log = Logger.getLogger(DownloadResults.class.getName());

    protected final ProjectRepository projectRepository;
    protected final SampleRepository sampleRepository;
    protected final TestResultRepository testResultRepository;
    protected final CSVStoreService csvStoreService;
    protected long minTimeStamp;

    public DownloadResults(ProjectRepository projectRepository, SampleRepository sampleRepository, TestResultRepository testResultRepository, CSVStoreService csvStoreService) {
        this.projectRepository = projectRepository;
        this.sampleRepository = sampleRepository;
        this.testResultRepository = testResultRepository;
        this.csvStoreService = csvStoreService;
    }

    @RequestMapping(value = "/get", method = RequestMethod.GET)
    public String get() {
        minTimeStamp = (System.currentTimeMillis() / 1000) - 60 * 60 * 24 * 4;
        StringBuilder result = new StringBuilder();
        testResultRepository.findAll().forEach(testResult -> {
            // create array of field from testResult, name, result:
            var fields = new String[]{testResult.sample.name, testResult.testParameter.type, csvStoreService.getPeaksFromResultFiles(testResult.getId()), testResult.description, testResult.resultText};
            result.append('"').append(String.join("\",\"", fields)).append("\"\n");
        });

        return result.toString();
    }

    @RequestMapping(value = "/project/{projectId:[\\d]+}", method = RequestMethod.GET)
    public ResponseEntity<Resource> getResultsForProject(@PathVariable long projectId) {
        var project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle boldStyle = createBoldStyle(workbook);

            createProjectInfoSheet(workbook, project, boldStyle);
            createTestResultsSheet(workbook, projectId);

            workbook.write(out);
            ByteArrayResource resource = new ByteArrayResource(out.toByteArray());

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=results_" + project.name + "_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx");
            headers.add(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

            return ResponseEntity.ok().headers(headers).contentLength(resource.contentLength()).body(resource);
        } catch (IOException e) {
            log.severe(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    private CellStyle createBoldStyle(Workbook workbook) {
        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        CellStyle boldStyle = workbook.createCellStyle();
        boldStyle.setFont(boldFont);
        return boldStyle;
    }

    private void createProjectInfoSheet(Workbook workbook, Project project, CellStyle boldStyle) {
        Sheet sheet1 = workbook.createSheet("Project Info");
        sheet1.setDefaultColumnStyle(0, boldStyle);
        sheet1.setColumnWidth(0, 5000);

        Font bigFont = workbook.createFont();
        bigFont.setFontHeight((short) 265);
        bigFont.setBold(true);
        CellStyle bigStyle = workbook.createCellStyle();
        bigStyle.setFont(bigFont);

        Row row = sheet1.createRow(0);
        row.createCell(0).setCellValue("Project:");
        row.getCell(0).setCellStyle(bigStyle);

        row = sheet1.createRow(1);
        row.createCell(0).setCellValue("Project Name");
        row.createCell(1).setCellValue(project.name);
        row = sheet1.createRow(2);
        row.createCell(0).setCellValue("Description");
        row.createCell(1).setCellValue(project.description);

        var customer = project.customer;

        row = sheet1.createRow(5);
        row.createCell(0).setCellValue("Customer:");
        row.getCell(0).setCellStyle(bigStyle);

        row = sheet1.createRow(6);
        row.createCell(0).setCellValue("Organization");
        row.createCell(1).setCellValue(customer.organization);
        row = sheet1.createRow(7);
        row.createCell(0).setCellValue("Name");
        row.createCell(1).setCellValue(customer.firstname + " " + customer.lastname);
        row = sheet1.createRow(8);
        row.createCell(0).setCellValue("Email");
        row.createCell(1).setCellValue(customer.email);
    }

    private void createTestResultsSheet(Workbook workbook, long projectId) {
        Sheet sheet2 = workbook.createSheet("Test Results");
        Row headerRow = sheet2.createRow(0);
        String[] headers = {"Test Result ID", "Sample Name", "Test Type", "Peaks (kN)", "Description", "Result Text", "Speed", "Upper Shut Off Threshold", "Lower Shut Off Threshold", "Upper Turn Force", "Lower Turn Force", "Cycle Count", "Start Ramp Seconds", "Stop Ramp Seconds", "Sample Description", "Sample Model", "Sample Manufacturer", "Year of Manufacture", "Gear Types", "Gear Standards", "Materials"};

        var boldStyle = createBoldStyle(workbook);

        for (int i = 0; i < headers.length; i++) {
            var cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(boldStyle);
        }

        int rowNum = 1;
        for (var testResult : testResultRepository.findByProjectId(projectId)) {
            var testParameter = testResult.testParameter;
            var sample = testResult.sample;
            Row row = sheet2.createRow(rowNum++);
            row.createCell(0).setCellValue(testResult.getId());
            row.createCell(1).setCellValue(sample.name);
            row.createCell(2).setCellValue(testResult.testParameter.type);
            row.createCell(3).setCellValue(csvStoreService.getPeaksFromResultFiles(testResult.getId()));
            row.createCell(4).setCellValue(testResult.description);
            row.createCell(5).setCellValue(testResult.resultText);

            row.createCell(6).setCellValue(testParameter.speed);
            setNullableCellValue(row.createCell(7), testParameter.upperShutOffThreshold);
            setNullableCellValue(row.createCell(8), testParameter.lowerShutOffThreshold);
            setNullableCellValue(row.createCell(9), testParameter.upperTurnForce);
            setNullableCellValue(row.createCell(10), testParameter.lowerTurnForce);
            setNullableCellValue(row.createCell(11), testParameter.cycleCount);
            setNullableCellValue(row.createCell(12), testParameter.startRampSeconds);
            setNullableCellValue(row.createCell(13), testParameter.stopRampSeconds);

            row.createCell(14).setCellValue(sample.description);
            row.createCell(15).setCellValue(sample.model);
            row.createCell(16).setCellValue(sample.manufacturer);
            row.createCell(17).setCellValue(sample.yearOfManufacture);
            row.createCell(18).setCellValue(sample.gearTypes.stream().map(gearType -> gearType.name).collect(Collectors.joining(", ")));
            row.createCell(19).setCellValue(sample.gearStandards.stream().map(gearStandard -> gearStandard.name).collect(Collectors.joining(", ")));
            row.createCell(20).setCellValue(sample.materials.stream().map(material -> material.name).collect(Collectors.joining(", ")));
        }

        for (int i = 0; i < headers.length; i++) {
            sheet2.autoSizeColumn(i);
        }
    }

    private <T> void setNullableCellValue(Cell cell, T value) {
        if (value != null) {
            if (value instanceof Double) {
                cell.setCellValue((Double) value);
            } else if (value instanceof Integer) {
                cell.setCellValue((Integer) value);
            } else if (value instanceof String) {
                cell.setCellValue((String) value);
            } else if (value instanceof Boolean) {
                cell.setCellValue((Boolean) value);
            } else {
                throw new IllegalArgumentException("Unsupported value type");
            }
        }
    }

    private ResponseEntity<FileSystemResource> createResponseEntity(File file) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + file.getName());
        headers.add(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(file));
    }
}