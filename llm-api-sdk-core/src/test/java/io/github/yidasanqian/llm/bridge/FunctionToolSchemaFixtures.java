package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.json.JsonUtil;

import java.util.Map;

public final class FunctionToolSchemaFixtures {

    private FunctionToolSchemaFixtures() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> taskCreateFunction() {
        return JsonUtil.toObject("""
                {
                  "name": "TaskCreate",
                  "description": "Create a task with arbitrary metadata.",
                  "strict": false,
                  "parameters": {
                    "$schema": "http://json-schema.org/draft-07/schema#",
                    "type": "object",
                    "additionalProperties": false,
                    "properties": {
                      "activeForm": {"type": "string"},
                      "description": {"type": "string"},
                      "metadata": {
                        "additionalProperties": {},
                        "description": "Arbitrary metadata to attach to the task",
                        "type": "object"
                      },
                      "owner": {"type": "string"},
                      "subject": {"type": "string"}
                    },
                    "required": ["subject", "description"]
                  }
                }
                """, Map.class);
    }
}
