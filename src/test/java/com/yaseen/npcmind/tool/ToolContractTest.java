package com.yaseen.npcmind.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolContractTest {
    private static final String SCHEMA = "{target:string, count:integer(optional), mode:attack|follow}";

    @Test
    void acceptsRequiredAndOptionalValuesAndNormalizesJson() {
        ToolContract.Validation result = ToolContract.validateSchema(
                SCHEMA, "{\"target\":\"oak\",\"mode\":\"attack\",\"count\":3}");
        assertTrue(result.ok(), result.reason());
        assertEquals("oak", result.normalized().get("target").getAsString());
        assertEquals(3, result.normalized().get("count").getAsInt());
    }

    @Test
    void rejectsMissingRequiredUnknownAndIncorrectlyTypedArguments() {
        assertEquals("missing_argument=target",
                ToolContract.validateSchema(SCHEMA, "{\"mode\":\"attack\"}").reason());
        assertTrue(ToolContract.validateSchema(SCHEMA,
                "{\"target\":\"oak\",\"mode\":\"attack\",\"surprise\":true}")
                .reason().startsWith("unknown_argument="));
        assertEquals("invalid_type=target:STRING",
                ToolContract.validateSchema(SCHEMA, "{\"target\":7,\"mode\":\"attack\"}").reason());
    }

    @Test
    void rejectsInvalidEnumsMalformedJsonAndNonObjectArguments() {
        assertEquals("invalid_enum=mode", ToolContract.validateSchema(SCHEMA,
                "{\"target\":\"oak\",\"mode\":\"delete\"}").reason());
        assertEquals("invalid_json_arguments", ToolContract.validateSchema(SCHEMA, "{broken").reason());
        assertEquals("arguments_not_object", ToolContract.validateSchema(SCHEMA, "[1,2]").reason());
        assertFalse(ToolContract.validateSchema("{}", "{}").normalized().has("unused"));
    }

    @Test
    void recognizesNestedObjectAndArraySchemaFields() {
        ToolContract.Validation result = ToolContract.validateSchema(
                "{payload:{x:number,y:string}, items:[string]}",
                "{\"payload\":{\"x\":1,\"y\":\"ok\"},\"items\":[\"a\"]}");
        assertTrue(result.ok(), result.reason());
        assertTrue(ToolContract.toJsonSchema(toolSchema("{payload:{x:number,y:string}, items:[string]}")).has("properties"));
    }

    private static AITool toolSchema(String schema) {
        return new AITool() {
            @Override public String name() { return "test"; }
            @Override public String description() { return "test"; }
            @Override public String schema() { return schema; }
            @Override public String execute(com.yaseen.npcmind.entity.CompanionEntity companion,
                                            net.minecraft.server.level.ServerPlayer owner, String arguments) {
                return "SUCCESS";
            }
        };
    }
}
