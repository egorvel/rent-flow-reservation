package com.rentflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rentflow.repository.ReservationRepository;
import com.rentflow.support.PostgresIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationIT extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/reservations";
    private static final String CREATE = """
        {"serialNumber":"DRILL-001","customerId":"CUSTOMER-001","orderId":"ORDER-001","startDate":"2026-10-01","endDate":"2026-10-03"}
        """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ReservationRepository repository;

    @BeforeEach
    void clearReservations() {
        repository.deleteAllInBatch();
    }

    @Test
    void createsHeldAndReturnsExactlyThePersistedRepresentation() throws Exception {
        Instant before = Instant.now().minusMillis(1);
        JsonNode created = create(CREATE);
        UUID id = UUID.fromString(created.path("id").asString());
        assertThat(created.propertyNames())
                .containsExactlyInAnyOrder(
                        "id", "serialNumber", "customerId", "orderId", "startDate", "endDate", "timestamp", "status");
        assertThat(created.path("status").asString()).isEqualTo("HELD");
        assertThat(Instant.parse(created.path("timestamp").asString())).isBetween(before, Instant.now());
        assertThat(read(id.toString())).isEqualTo(created);
        assertThat(repository.findById(id).orElseThrow().getTimestamp().getNano() % 1000)
                .isZero();
    }

    @Test
    void acceptsDuplicateSerialAndCustomerEvenForTheSamePeriod() throws Exception {
        JsonNode first = create(CREATE);
        JsonNode second = create(CREATE);
        assertThat(second.path("id").asString()).isNotEqualTo(first.path("id").asString());
        assertThat(repository.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HELD", "CONFIRMED", "CANCELLED"})
    void replacementUpdatesAllMutableFieldsAndPreservesManagedFields(String newStatus) throws Exception {
        JsonNode original = create(CREATE);
        String replacement = """
            {"serialNumber":"SAW-002","customerId":"CUSTOMER-002","orderId":"ORDER-002","startDate":"2027-01-01","endDate":"2027-01-02","status":"%s"}
            """.formatted(newStatus);
        JsonNode updated = body(mvc.perform(put(PATH + "/" + original.path("id").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("SAW-002"))
                .andExpect(jsonPath("$.customerId").value("CUSTOMER-002"))
                .andExpect(jsonPath("$.orderId").value("ORDER-002"))
                .andExpect(jsonPath("$.startDate").value("2027-01-01"))
                .andExpect(jsonPath("$.endDate").value("2027-01-02"))
                .andExpect(jsonPath("$.status").value(newStatus)));
        assertThat(updated.path("id")).isEqualTo(original.path("id"));
        assertThat(updated.path("timestamp")).isEqualTo(original.path("timestamp"));
        assertThat(read(updated.path("id").asString())).isEqualTo(updated);
    }

    @Test
    void statusTransitionsAreUnrestrictedInThisScaffold() throws Exception {
        String id = create(CREATE).path("id").asString();
        for (String value : List.of("CANCELLED", "CONFIRMED", "HELD")) {
            mvc.perform(put(PATH + "/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(withStatus(CREATE, value)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(value));
        }
    }

    @Test
    void permanentlyDeletesAndReturnsNotFoundThereafter() throws Exception {
        String id = create(CREATE).path("id").asString();
        mvc.perform(delete(PATH + "/" + id))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        problem(get(PATH + "/" + id), 404, "RESERVATION_NOT_FOUND");
        problem(delete(PATH + "/" + id), 404, "RESERVATION_NOT_FOUND");
        assertThat(repository.count()).isZero();
    }

    @Test
    void missingGetReplaceAndDeleteNeverCreate() throws Exception {
        String path = PATH + "/" + UUID.randomUUID();
        problem(get(path), 404, "RESERVATION_NOT_FOUND");
        problem(
                put(path).contentType(MediaType.APPLICATION_JSON).content(withStatus(CREATE, "HELD")),
                404,
                "RESERVATION_NOT_FOUND");
        problem(delete(path), 404, "RESERVATION_NOT_FOUND");
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidCreateBodies")
    void rejectsInvalidCreationWithoutWriting(String request) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(repository.count()).isZero();
    }

    static Stream<String> invalidCreateBodies() {
        return Stream.of(
                "",
                "{",
                "{}",
                "null",
                "[]",
                CREATE.replace("DRILL-001", ""),
                CREATE.replace("DRILL-001", "/bad"),
                CREATE.replace("DRILL-001", "X".repeat(65)),
                CREATE.replace("CUSTOMER-001", " "),
                CREATE.replace("CUSTOMER-001", "X".repeat(65)),
                CREATE.replace("ORDER-001", ""),
                CREATE.replace("ORDER-001", "X".repeat(65)),
                CREATE.replace("\"CUSTOMER-001\"", "null"),
                CREATE.replace("\"ORDER-001\"", "{}"),
                CREATE.replace("\"2026-10-01\"", "null"),
                CREATE.replace("\"2026-10-01\"", "123"),
                CREATE.replace("2026-10-01", "2026-10-04"),
                CREATE.replace("2026-10-01", "2026-02-30"),
                CREATE.replace("2026-10-01", "0000-01-01"),
                CREATE.replace("2026-10-03", "+10000-01-01"),
                CREATE.replace("2026-10-01", "2026-10-01T12:00:00Z"),
                withStatus(CREATE, "HELD"),
                CREATE.replace("}", ",\"id\":\"ef469102-af79-4a47-9afb-f34937c9481f\"}"),
                CREATE.replace("}", ",\"timestamp\":\"2026-01-01T00:00:00Z\"}"),
                CREATE.replace("}", ",\"unknown\":true}"));
    }

    @Test
    void rejectedReplacementsPreserveTheEntireOriginal() throws Exception {
        JsonNode original = create(CREATE);
        String id = original.path("id").asString();
        for (String invalid : List.of(
                CREATE,
                "{",
                withStatus(CREATE, "EXPIRED"),
                withStatus(CREATE, "held"),
                withStatus(CREATE, "0"),
                withStatus(CREATE, "HELD").replace("2026-10-03", "2026-09-30"),
                withStatus(CREATE, "HELD").replace("}", ",\"id\":\"" + id + "\"}"))) {
            mvc.perform(put(PATH + "/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalid))
                    .andExpect(status().isBadRequest());
            assertThat(read(id)).isEqualTo(original);
        }
        assertThat(repository.count()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-01", "2000-01-01", "0001-01-01", "9999-12-31"})
    void acceptsSameDayHistoricalAndBoundaryYears(String day) throws Exception {
        create(CREATE.replace("2026-10-01", day).replace("2026-10-03", day));
    }

    @ParameterizedTest
    @MethodSource("invalidReplacementBodies")
    void sharedDtoRejectsInvalidReplacementWithoutChangingPersistedData(String request) throws Exception {
        JsonNode original = create(CREATE);
        String id = original.path("id").asString();
        mvc.perform(put(PATH + "/" + id).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(read(id)).isEqualTo(original);
        assertThat(repository.count()).isOne();
    }

    static Stream<String> invalidReplacementBodies() {
        String valid = withStatus(CREATE, "CONFIRMED");
        return Stream.of(
                "{}",
                "null",
                "[]",
                "",
                valid.replace("\"serialNumber\":\"DRILL-001\",", ""),
                valid.replace("\"customerId\":\"CUSTOMER-001\",", ""),
                valid.replace("\"orderId\":\"ORDER-001\",", ""),
                valid.replace("\"startDate\":\"2026-10-01\",", ""),
                valid.replace("\"endDate\":\"2026-10-03\",", ""),
                CREATE,
                valid.replace("DRILL-001", ""),
                valid.replace("DRILL-001", "/bad"),
                valid.replace("DRILL-001", "X".repeat(65)),
                valid.replace("CUSTOMER-001", " "),
                valid.replace("CUSTOMER-001", "X".repeat(65)),
                valid.replace("ORDER-001", " "),
                valid.replace("ORDER-001", "X".repeat(65)),
                valid.replace("\"CUSTOMER-001\"", "{}"),
                valid.replace("\"ORDER-001\"", "[]"),
                valid.replace("\"CONFIRMED\"", "null"),
                valid.replace("\"CONFIRMED\"", "1"),
                valid.replace("\"CONFIRMED\"", "true"),
                valid.replace("2026-10-01", "2026-02-30"),
                valid.replace("2026-10-03", "2026-09-30"),
                valid.replace("}", ",\"unknown\":true}"),
                valid.replace("}", ",\"id\":null}"),
                valid.replace("}", ",\"id\":\"ef469102-af79-4a47-9afb-f34937c9481f\"}"),
                valid.replace("}", ",\"timestamp\":null}"),
                valid.replace("}", ",\"timestamp\":\"2026-01-01T00:00:00Z\"}"));
    }

    @Test
    void missingFieldsProduceSortedViolations() throws Exception {
        problem(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.violations", hasSize(5)))
                .andExpect(jsonPath("$.violations[0].field").value("customerId"))
                .andExpect(jsonPath("$.violations[1].field").value("endDate"))
                .andExpect(jsonPath("$.violations[2].field").value("orderId"))
                .andExpect(jsonPath("$.violations[3].field").value("serialNumber"))
                .andExpect(jsonPath("$.violations[4].field").value("startDate"));
    }

    @Test
    void returnsAStableBoundedPageEnvelopeAndAccurateEmptyPages() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(create(CREATE).path("id").asString());
        }
        ids.sort(Comparator.naturalOrder());
        JsonNode page = body(mvc.perform(get(PATH)).andExpect(status().isOk()));
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("content", "page");
        assertThat(page.path("page").propertyNames())
                .containsExactlyInAnyOrder("size", "number", "totalElements", "totalPages");
        assertThat(page.path("content")
                        .valueStream()
                        .map(node -> node.path("id").asString())
                        .toList())
                .containsExactlyElementsOf(ids);
        mvc.perform(get(PATH).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(2)))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        mvc.perform(get(PATH).param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.number").value(5));
        mvc.perform(get(PATH).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
    }

    @ParameterizedTest
    @CsvSource({
        "id,asc",
        "id,desc",
        "serialNumber,asc",
        "serialNumber,desc",
        "customerId,asc",
        "customerId,desc",
        "orderId,asc",
        "orderId,desc",
        "startDate,asc",
        "startDate,desc",
        "endDate,asc",
        "endDate,desc",
        "timestamp,asc",
        "timestamp,desc",
        "status,asc",
        "status,desc"
    })
    void sortsAllFieldsDeterministically(String field, String direction) throws Exception {
        List<JsonNode> expected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String input = i == 0 ? CREATE.replace("001", "002").replace("2026-10-01", "2026-10-02") : CREATE;
            JsonNode created = create(input);
            String replacement = withStatus(input, i == 0 ? "CANCELLED" : "CONFIRMED");
            expected.add(body(mvc.perform(put(PATH + "/" + created.path("id").asString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replacement))
                    .andExpect(status().isOk())));
        }
        Comparator<JsonNode> comparator =
                Comparator.comparing(node -> node.path(field).asString());
        if (direction.equals("desc")) {
            comparator = comparator.reversed();
        }
        if (!field.equals("id")) {
            comparator = comparator.thenComparing(node -> node.path("id").asString());
        }
        expected.sort(comparator);
        JsonNode page = body(mvc.perform(
                        get(PATH).param("sort", field).param("direction", direction.toUpperCase(java.util.Locale.ROOT)))
                .andExpect(status().isOk()));
        assertThat(page.path("content").valueStream().toList()).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "page,-1",
        "page,nope",
        "page,2147483648",
        "page,2147483647",
        "size,0",
        "size,101",
        "size,2.5",
        "sort,unknown",
        "direction,sideways",
        "filter,x"
    })
    void rejectsInvalidCollectionParameters(String parameter, String value) throws Exception {
        problem(get(PATH).param(parameter, value), 400, "VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "size", "sort", "direction"})
    void rejectsBlankAndRepeatedParameters(String parameter) throws Exception {
        problem(get(PATH).param(parameter, ""), 400, "VALIDATION_FAILED");
        String value =
                switch (parameter) {
                    case "page" -> "0";
                    case "size" -> "20";
                    case "sort" -> "id";
                    default -> "asc";
                };
        problem(get(PATH).param(parameter, value, value), 400, "VALIDATION_FAILED");
    }

    @Test
    void frameworkErrorsUseProblemDetailsAndPreserveProtocolHeaders() throws Exception {
        problem(get(PATH + "/not-a-uuid"), 400, "VALIDATION_FAILED");
        problem(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{"), 400, "MALFORMED_JSON");
        problem(
                        patch(PATH + "/" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        405,
                        "METHOD_NOT_ALLOWED")
                .andExpect(header().string("Allow", containsString("PUT")));
        problem(post(PATH).contentType(MediaType.TEXT_PLAIN).content(CREATE), 415, "UNSUPPORTED_MEDIA_TYPE");
        problem(get(PATH).accept(MediaType.APPLICATION_XML), 406, "NOT_ACCEPTABLE");
        problem(get("/api/v1/missing"), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void healthProbesExposeOnlyStatusAndOtherActuatorEndpointsStayHidden() throws Exception {
        for (String path : List.of("/livez", "/readyz", "/actuator/health")) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist());
        }
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    private JsonNode create(String request) throws Exception {
        ResultActions result = mvc.perform(
                        post(PATH).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
        JsonNode created = body(result);
        result.andExpect(
                header().string("Location", PATH + "/" + created.path("id").asString()));
        return created;
    }

    private JsonNode read(String id) throws Exception {
        return body(mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk()));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private static String withStatus(String request, String value) {
        return request.replace("}", ",\"status\":\"" + value + "\"}");
    }

    private ResultActions problem(MockHttpServletRequestBuilder request, int statusCode, String code) throws Exception {
        return mvc.perform(request)
                .andExpect(status().is(statusCode))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(statusCode))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type")
                        .value("urn:rentflow:problem:"
                                + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')))
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").isString());
    }
}
