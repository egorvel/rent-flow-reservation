package com.rentflow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.rentflow.dto.CreateReservationItemRequest;
import com.rentflow.dto.CreateReservationsRequest;
import com.rentflow.dto.ProblemResponse;
import com.rentflow.dto.ReservationDTO;
import com.rentflow.model.InventoryClaimResult;
import com.rentflow.service.InventoryGateway;
import com.rentflow.support.PostgresIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiIT extends PostgresIntegrationTest {
    private static final String COLLECTION = "/api/v1/reservations";
    private static final String ITEM = COLLECTION + "/{id}";
    private static final Set<String> INPUT_FIELDS = Set.of("customerId", "orderId", "items");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private Validator validator;

    @MockitoBean
    private InventoryGateway inventoryGateway;

    private JsonNode document;

    @BeforeEach
    void readDocument() throws Exception {
        when(inventoryGateway.claim(any(), any())).thenReturn(InventoryClaimResult.claimed());
        document = mapper.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
    }

    @Test
    void exposesExactlyTheFiveOperationsAndUsableSwaggerUi() throws Exception {
        assertThat(document.path("openapi").asString()).startsWith("3.");
        assertThat(document.at("/info/title").asString()).isEqualTo("RentFlow Reservation API");
        assertThat(document.path("paths").propertyNames()).containsExactlyInAnyOrder(COLLECTION, ITEM);
        assertThat(document.path("paths").path(COLLECTION).propertyNames()).containsExactlyInAnyOrder("get", "post");
        assertThat(document.path("paths").path(ITEM).propertyNames()).containsExactlyInAnyOrder("get", "put", "delete");
        assertThat(operation(COLLECTION, "post").path("operationId").asString()).isEqualTo("createReservation");
        assertThat(operation(COLLECTION, "get").path("operationId").asString()).isEqualTo("listReservations");
        assertThat(operation(ITEM, "get").path("operationId").asString()).isEqualTo("getReservation");
        assertThat(operation(ITEM, "put").path("operationId").asString()).isEqualTo("replaceReservation");
        assertThat(operation(ITEM, "delete").path("operationId").asString()).isEqualTo("deleteReservation");
        assertThat(document.at("/components/securitySchemes").isMissingNode()).isTrue();
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
        assertReferencesResolve(document);
    }

    @Test
    void sharedReplacementAndResponseSchemaMarksManagedFieldsReadOnly() {
        JsonNode create = schema("CreateReservationsRequest");
        JsonNode createItem = schema("CreateReservationItemRequest");
        JsonNode response = schema("ReservationDTO");
        assertThat(schema("ReplaceReservationRequest").isMissingNode()).isTrue();
        assertThat(create.path("properties").propertyNames()).containsExactlyInAnyOrderElementsOf(INPUT_FIELDS);
        assertThat(strings(create.path("required"))).containsExactlyInAnyOrderElementsOf(INPUT_FIELDS);
        assertThat(response.path("properties").propertyNames().stream()
                        .filter(name -> !response.path("properties")
                                .path(name)
                                .path("readOnly")
                                .asBoolean())
                        .toList())
                .containsExactlyInAnyOrder("serialNumber", "customerId", "orderId", "startDate", "endDate", "status");
        assertThat(strings(response.path("required")).stream()
                        .filter(name -> !response.path("properties")
                                .path(name)
                                .path("readOnly")
                                .asBoolean())
                        .toList())
                .containsExactlyInAnyOrder("serialNumber", "customerId", "orderId", "startDate", "endDate", "status");
        assertThat(response.path("properties").propertyNames())
                .containsExactlyInAnyOrder(
                        "id", "serialNumber", "customerId", "orderId", "startDate", "endDate", "timestamp", "status");
        assertThat(response.at("/properties/id/format").asString()).isEqualTo("uuid");
        assertThat(response.at("/properties/timestamp/format").asString()).isEqualTo("date-time");
        assertThat(response.at("/properties/id/readOnly").asBoolean()).isTrue();
        assertThat(response.at("/properties/timestamp/readOnly").asBoolean()).isTrue();
        assertThat(strings(response.path("required")))
                .containsExactlyInAnyOrder(
                        "id", "serialNumber", "customerId", "orderId", "startDate", "endDate", "timestamp", "status");
        for (String name : List.of("ReservationDTO")) {
            JsonNode properties = schema(name).path("properties");
            assertThat(properties.at("/serialNumber/pattern").asString())
                    .isEqualTo(ReservationDTO.SERIAL_NUMBER_PATTERN);
            assertThat(properties.at("/serialNumber/maxLength").asInt()).isEqualTo(64);
            assertThat(properties.at("/customerId/maxLength").asInt()).isEqualTo(64);
            assertThat(properties.at("/orderId/maxLength").asInt()).isEqualTo(64);
            assertThat(properties.at("/startDate/format").asString()).isEqualTo("date");
            assertThat(properties.at("/endDate/format").asString()).isEqualTo("date");
        }
        assertThat(create.at("/properties/customerId/maxLength").asInt()).isEqualTo(64);
        assertThat(create.at("/properties/orderId/maxLength").asInt()).isEqualTo(64);
        assertThat(create.at("/properties/items/minItems").asInt()).isEqualTo(1);
        assertThat(create.at("/properties/items/maxItems").asInt()).isEqualTo(100);
        assertThat(createItem.path("properties").propertyNames())
                .containsExactlyInAnyOrder("serialNumber", "startDate", "endDate");
        assertThat(strings(response.at("/properties/status/enum")))
                .containsExactlyInAnyOrder("HELD", "CONFIRMED", "CANCELLED");
        assertThat(operation(COLLECTION, "post")
                        .at("/requestBody/content/application~1json/schema/$ref")
                        .asString())
                .endsWith("/CreateReservationsRequest");
        assertThat(operation(ITEM, "put")
                        .at("/requestBody/content/application~1json/schema/$ref")
                        .asString())
                .endsWith("/ReservationDTO");
    }

    @Test
    void documentsSuccessErrorsIdempotencyAndPaging() {
        assertThat(operation(COLLECTION, "post").path("responses").propertyNames())
                .contains("201", "400", "405", "406", "409", "415", "422", "500", "502", "503");
        assertThat(operation(COLLECTION, "post")
                        .at("/responses/201/headers/Location")
                        .isMissingNode())
                .isTrue();
        assertThat(operation(COLLECTION, "post")
                        .at("/responses/201/content/application~1json/schema/items/$ref")
                        .asString())
                .endsWith("/ReservationDTO");
        assertThat(operation(COLLECTION, "post")
                        .at("/responses/201/headers/Idempotency-Replayed")
                        .isMissingNode())
                .isFalse();
        assertThat(operation(COLLECTION, "post")
                        .at("/responses/409/headers/Retry-After")
                        .isMissingNode())
                .isFalse();
        for (String method : List.of("get", "put", "delete")) {
            JsonNode responses = operation(ITEM, method).path("responses");
            assertThat(responses.propertyNames())
                    .contains("400", "404", "405", "406", "500", method.equals("delete") ? "204" : "200");
            for (String code : List.of("400", "404", "405", "406", "500")) {
                assertThat(responses
                                .path(code)
                                .at("/content/application~1problem+json/schema/$ref")
                                .asString())
                        .endsWith("/ProblemResponse");
            }
        }
        assertThat(operation(ITEM, "delete").at("/responses/204/content").isMissingNode())
                .isTrue();
        assertThat(parameter("page").at("/schema/default").asInt()).isZero();
        assertThat(parameter("size").at("/schema/default").asInt()).isEqualTo(20);
        assertThat(parameter("size").at("/schema/maximum").asInt()).isEqualTo(100);
        assertThat(parameter("sort").at("/schema/default").asString()).isEqualTo("id");
        assertThat(strings(parameter("sort").at("/schema/enum")))
                .containsExactlyInAnyOrder(
                        "id", "serialNumber", "customerId", "orderId", "startDate", "endDate", "timestamp", "status");
        JsonNode page = resolve(operation(COLLECTION, "get").at("/responses/200/content/application~1json/schema"));
        assertThat(page.path("properties").propertyNames()).containsExactlyInAnyOrder("content", "page");
        assertThat(page.at("/properties/content/items/$ref").asString()).endsWith("/ReservationDTO");
    }

    @Test
    void examplesDeserializeAndMeetBeanConstraints() {
        for (Map.Entry<String, Class<?>> entry : Map.<String, Class<?>>of(
                        "CreateReservationsRequest",
                        CreateReservationsRequest.class,
                        "ProblemResponse",
                        ProblemResponse.class)
                .entrySet()) {
            JsonNode example = schema(entry.getKey()).path("example");
            assertThat(example.isMissingNode()).isFalse();
            if (example.isString()) {
                example = mapper.readTree(example.asString());
            }
            Object value = mapper.treeToValue(example, entry.getValue());
            assertThat(validator.validate(value)).isEmpty();
        }
    }

    @Test
    void sharedDtoExamplesRespectInputAndOutputContracts() throws Exception {
        JsonNode responseExample = schema("ReservationDTO").path("example");
        if (responseExample.isString()) {
            responseExample = mapper.readTree(responseExample.asString());
        }
        // Construct a response directly: its read-only metadata is intentionally rejected by the input mapper.
        ReservationDTO response = new ReservationDTO(
                UUID.fromString(responseExample.path("id").asString()),
                responseExample.path("serialNumber").asString(),
                responseExample.path("customerId").asString(),
                responseExample.path("orderId").asString(),
                LocalDate.parse(responseExample.path("startDate").asString()),
                LocalDate.parse(responseExample.path("endDate").asString()),
                Instant.parse(responseExample.path("timestamp").asString()),
                responseExample.path("status").asString());
        assertThat(validator.validate(response)).isEmpty();
        JsonNode serializedResponse = mapper.valueToTree(response);
        assertThat(serializedResponse).isEqualTo(responseExample);

        JsonNode replacementExample =
                operation(ITEM, "put").at("/requestBody/content/application~1json/examples/replacement/value");
        if (replacementExample.isString()) {
            replacementExample = mapper.readTree(replacementExample.asString());
        }
        assertThat(replacementExample.propertyNames())
                .containsExactlyInAnyOrder("serialNumber", "customerId", "orderId", "startDate", "endDate", "status");
        ReservationDTO request = mapper.treeToValue(replacementExample, ReservationDTO.class);
        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.id()).isNull();
        assertThat(request.timestamp()).isNull();

        CreateReservationsRequest creation = new CreateReservationsRequest(
                request.customerId(),
                request.orderId(),
                List.of(new CreateReservationItemRequest(
                        request.serialNumber(), request.startDate(), request.endDate())));
        JsonNode created = mapper.readTree(mvc.perform(post(COLLECTION)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(creation)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
        JsonNode replaced = mapper.readTree(mvc.perform(
                        put(COLLECTION + "/" + created.get(0).path("id").asString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(replacementExample)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.get(0).path("id").asString()))
                .andExpect(jsonPath("$.timestamp")
                        .value(created.get(0).path("timestamp").asString()))
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
        for (String field : replacementExample.propertyNames()) {
            assertThat(replaced.path(field)).as(field).isEqualTo(replacementExample.path(field));
        }
    }

    private JsonNode operation(String path, String method) {
        return document.path("paths").path(path).path(method);
    }

    private JsonNode schema(String name) {
        return document.at("/components/schemas/" + name);
    }

    private List<String> strings(JsonNode node) {
        return node.valueStream().map(JsonNode::asString).toList();
    }

    private JsonNode resolve(JsonNode node) {
        return node.has("$ref") ? document.at(node.path("$ref").asString().substring(1)) : node;
    }

    private JsonNode parameter(String name) {
        return operation(COLLECTION, "get")
                .path("parameters")
                .valueStream()
                .filter(node -> node.path("name").asString().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private void assertReferencesResolve(JsonNode node) {
        if (node.has("$ref")) {
            assertThat(resolve(node).isMissingNode())
                    .as(node.path("$ref").asString())
                    .isFalse();
        }
        if (node.isContainer()) {
            node.valueStream().forEach(this::assertReferencesResolve);
        }
    }
}
