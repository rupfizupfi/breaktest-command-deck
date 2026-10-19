package ch.rupfizupfi.deck.security;

import ch.rupfizupfi.deck.data.CustomerRepository;
import ch.rupfizupfi.deck.data.FileMetadata;
import ch.rupfizupfi.deck.data.FileMetadataRepository;
import ch.rupfizupfi.deck.data.Project;
import ch.rupfizupfi.deck.data.ProjectRepository;
import ch.rupfizupfi.deck.data.Sample;
import ch.rupfizupfi.deck.data.SampleRepository;
import ch.rupfizupfi.deck.data.TestParameter;
import ch.rupfizupfi.deck.data.TestParameterRepository;
import ch.rupfizupfi.deck.data.TestResult;
import ch.rupfizupfi.deck.data.TestResultRepository;
import ch.rupfizupfi.deck.data.TestType;
import ch.rupfizupfi.deck.data.UserRepository;
import ch.rupfizupfi.deck.filesystem.StorageLocationService;
import jakarta.servlet.Filter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Only the hardware button box reaches {@code /api/**} without a session; the export resolves its
 * project and its rows through the owner scope, and an image is served only through a file row the
 * caller may read. Storage is redirected to a temp directory, so this class gets its own context
 * and its own in-memory H2 (with the dev accounts) rather than sharing {@code ApplicationContextTest}'s.
 *
 * <p>An unlisted {@code /api} path is not probed: it falls through to Hilla's route matcher, which
 * needs the Vaadin servlet initializer that a MockMvc context never runs.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cms-api-security-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
        "vaadin.launch-browser=false"
})
@ActiveProfiles("dev")
class ApiSecurityTest {

    private static final byte[] IMAGE_BYTES = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    @Autowired
    WebApplicationContext context;

    @Autowired
    Filter springSecurityFilterChain;

    @Autowired
    ProjectRepository projectRepository;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SampleRepository sampleRepository;

    @Autowired
    TestParameterRepository testParameterRepository;

    @Autowired
    TestResultRepository testResultRepository;

    @Autowired
    FileMetadataRepository fileMetadataRepository;

    @MockitoBean
    StorageLocationService storageLocationService;

    @TempDir
    static Path storage;

    private MockMvc mvc;

    @BeforeEach
    void createMockMvc() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        when(storageLocationService.getUploadLocation()).thenReturn(Files.createDirectories(storage.resolve("uploads")));
        when(storageLocationService.getResultDataLocation()).thenReturn(Files.createDirectories(storage.resolve("results")));
    }

    @Test
    void theButtonBoxNeedsNoSession() throws Exception {
        assertThat(mvc.perform(get("/api/ControllerEndpoint/press/start")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void anonymousDownloadIsRefused() throws Exception {
        int status = mvc.perform(get("/api/DownloadEndpoint/project/1")).andReturn().getResponse().getStatus();

        assertThat(status).isIn(302, 401, 403);
    }

    @Test
    void anonymousImageReadIsRefused() throws Exception {
        int status = mvc.perform(get("/api/files/image/1.png")).andReturn().getResponse().getStatus();

        assertThat(status).isIn(302, 401, 403);
    }

    @Test
    void anonymousUploadIsRefused() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", new byte[]{1});
        int status = mvc.perform(multipart("/api/files/upload").file(file)).andReturn().getResponse().getStatus();

        assertThat(status).isIn(302, 401, 403);
    }

    @Test
    void uploadWithoutCsrfTokenIsForbidden() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", IMAGE_BYTES);

        int status = mvc.perform(multipart("/api/files/upload").file(file).session(sessionOf("user", "ROLE_USER")))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    /**
     * The real token path, not spring-security-test's {@code csrf()}, which skips the token check
     * altogether: the token is the one Vaadin writes into the {@code _csrf} meta tag, the request's
     * {@link CsrfToken}, masked by Spring's default {@code XorCsrfTokenRequestAttributeHandler}.
     */
    @Test
    void uploadWithCsrfTokenIsAccepted() throws Exception {
        MockHttpSession session = sessionOf("user", "ROLE_USER");
        CsrfToken token = csrfTokenOf(session);
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", IMAGE_BYTES);

        MockHttpServletResponse response = mvc.perform(multipart("/api/files/upload").file(file).session(session)
                        .header(token.getHeaderName(), token.getToken()))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"fileName\":\"x.png\"");
    }

    /** The classic misfire: the unmasked session value is not what the XOR handler decodes. */
    @Test
    void uploadWithTheUnmaskedSessionTokenIsForbidden() throws Exception {
        MockHttpSession session = sessionOf("user", "ROLE_USER");
        CsrfToken masked = csrfTokenOf(session);
        CsrfToken raw = (CsrfToken) session.getAttribute(HttpSessionCsrfTokenRepository.class.getName() + ".CSRF_TOKEN");
        assertThat(raw.getToken()).isNotEqualTo(masked.getToken());
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", IMAGE_BYTES);

        int status = mvc.perform(multipart("/api/files/upload").file(file).session(session)
                        .header(raw.getHeaderName(), raw.getToken()))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    @Test
    void anotherOwnersProjectIsNotFound() throws Exception {
        Project foreign = project("admin");

        int status = mvc.perform(get("/api/DownloadEndpoint/project/" + foreign.getId()).session(sessionOf("user", "ROLE_USER")))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
    }

    @Test
    void anOwnProjectExports() throws Exception {
        Project own = project("user");

        int status = mvc.perform(get("/api/DownloadEndpoint/project/" + own.getId()).session(sessionOf("user", "ROLE_USER")))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
    }

    @Test
    void aSharedProjectExportsOnlyTheCallersResults() throws Exception {
        Project shared = project(null);
        TestResult own = testResult(shared, "user");
        testResult(shared, "admin");

        MockHttpServletResponse response = mvc.perform(get("/api/DownloadEndpoint/project/" + shared.getId()).session(sessionOf("user", "ROLE_USER")))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(exportedResultIds(response)).containsExactly(own.getId());
        // The non-ASCII project name travels as an RFC 5987 parameter, not as raw header bytes.
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment;")
                .contains("filename*=UTF-8''results_Pr%C3%BCfung");
    }

    @Test
    void adminExportSeesAllResults() throws Exception {
        Project shared = project(null);
        TestResult own = testResult(shared, "user");
        TestResult foreign = testResult(shared, "admin");

        MockHttpServletResponse response = mvc.perform(get("/api/DownloadEndpoint/project/" + shared.getId()).session(sessionOf("admin", "ROLE_USER", "ROLE_ADMIN")))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(exportedResultIds(response)).containsExactlyInAnyOrder(own.getId(), foreign.getId());
    }

    @Test
    void anotherOwnersImageIsNotFound() throws Exception {
        String fileName = storedImage(testResult(project("admin"), "admin"));

        int status = mvc.perform(get("/api/files/image/" + fileName).session(sessionOf("user", "ROLE_USER")))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
    }

    @Test
    void ownImageIsServed() throws Exception {
        String fileName = storedImage(testResult(project("user"), "user"));

        MockHttpServletResponse response = mvc.perform(get("/api/files/image/" + fileName).session(sessionOf("user", "ROLE_USER")))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(IMAGE_BYTES);
    }

    @Test
    void unknownFileNameIsNotFound() throws Exception {
        int status = mvc.perform(get("/api/files/image/987654321.png").session(sessionOf("user", "ROLE_USER")))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
    }

    @Test
    void aReadableRowWhoseBytesAreGoneIsNotFound() throws Exception {
        String fileName = storedImage(testResult(project("user"), "user"));
        Files.delete(storage.resolve("uploads").resolve(fileName));

        int status = mvc.perform(get("/api/files/image/" + fileName).session(sessionOf("user", "ROLE_USER")))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
    }

    /** Data rows of the "Test Results" sheet, by their first column. */
    private static List<Long> exportedResultIds(MockHttpServletResponse response) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            Sheet results = workbook.getSheet("Test Results");
            return StreamSupport.stream(results.spliterator(), false)
                    .filter(row -> row.getRowNum() > 0)
                    .map((Row row) -> (long) row.getCell(0).getNumericCellValue())
                    .toList();
        }
    }

    /** A {@code null} owner makes a shared project, which every user resolves. */
    private Project project(String ownerUsername) {
        Project project = new Project();
        project.name = ownerUsername == null ? "Prüfung shared" : "Export of " + ownerUsername;
        project.owner = ownerUsername == null ? null : userRepository.findByUsername(ownerUsername);
        project.customer = customerRepository.findAll().getFirst();
        return projectRepository.save(project);
    }

    private TestResult testResult(Project project, String ownerUsername) {
        Sample sample = new Sample();
        sample.project = project;
        sample.name = "gear of " + ownerUsername;
        // Bean validation runs on persist; the int default 0 violates @Min(1900).
        sample.yearOfManufacture = 2024;
        sample.gearTypes = new ArrayList<>();
        sample.gearStandards = new ArrayList<>();
        sample.materials = new ArrayList<>();
        sample = sampleRepository.save(sample);

        TestParameter parameter = new TestParameter();
        parameter.type = TestType.DESTRUCTIVE;
        parameter.speed = 10;
        parameter = testParameterRepository.save(parameter);

        TestResult result = new TestResult();
        result.owner = userRepository.findByUsername(ownerUsername);
        result.sample = sample;
        result.testParameter = parameter;
        return testResultRepository.save(result);
    }

    /** A file row on {@code result} named {@code <id>.png}, as {@code FileService} names one, with its bytes on disk. */
    private String storedImage(TestResult result) throws Exception {
        FileMetadata row = new FileMetadata("photo.png");
        row.setTestResult(result);
        row = fileMetadataRepository.save(row);
        String fileName = row.getId() + ".png";
        row.setFilePath(fileName);
        fileMetadataRepository.save(row);
        Files.write(storage.resolve("uploads").resolve(fileName), IMAGE_BYTES);
        return fileName;
    }

    /**
     * The token a page rendered for {@code session} carries: any request through the filter chain
     * exposes it as a request attribute, and reading it stores the raw value in the session.
     */
    private CsrfToken csrfTokenOf(MockHttpSession session) throws Exception {
        var request = mvc.perform(get("/api/files/image/987654321.png").session(session)).andReturn().getRequest();
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        assertThat(token).isNotNull();
        token.getToken();
        return token;
    }

    private static MockHttpSession sessionOf(String username, String... authorities) {
        var principal = new org.springframework.security.core.userdetails.User(username, "n/a", AuthorityUtils.createAuthorityList(authorities));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(authentication));
        return session;
    }
}
