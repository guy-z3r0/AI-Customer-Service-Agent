package com.ulab.agent.brain.tools;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The actions the model is allowed to take, described once in a shape both
 * vendors accept.
 *
 * Gemini calls these function declarations and OpenAI calls them functions, but
 * underneath both want the same thing — a name, a sentence saying when to use
 * it, and a JSON Schema for its arguments — so the schema is written once here
 * and each provider only wraps it.
 *
 * Seven actions: two that steer the call, one that ends it, three that touch
 * the customer records, and one that hands the call to a person.
 */
@Component
public class ToolRegistry {

    public static final String SET_LANGUAGE = "set_language";
    public static final String SET_MODE = "set_mode";
    public static final String END_CALL = "end_call";
    public static final String LOOKUP_CLIENT = "lookup_client";
    public static final String CREATE_CLIENT = "create_client";
    public static final String LOG_REQUEST = "log_request";
    public static final String ESCALATE_TO_HUMAN = "escalate_to_human";

    private static final List<String> SCHEMAS = List.of(
            """
            {
              "name": "set_language",
              "description": "Switch the call to the other language. Use it when the caller \
            asks for it, or is clearly speaking it already.",
              "parameters": {
                "type": "object",
                "properties": {
                  "language": {
                    "type": "string",
                    "enum": ["en", "bn"],
                    "description": "en for English, bn for Bangla"
                  }
                },
                "required": ["language"]
              }
            }""",
            """
            {
              "name": "set_mode",
              "description": "Say that this call is a different kind of call from the one you \
            thought. Use it once, when you are sure.",
              "parameters": {
                "type": "object",
                "properties": {
                  "mode": {
                    "type": "string",
                    "enum": ["EXISTING_CUSTOMER", "WRONG_NUMBER", "COMPLEX_REQUEST"],
                    "description": "EXISTING_CUSTOMER when the caller turns out to be on the \
            books, WRONG_NUMBER for a wrong number or a nuisance call, COMPLEX_REQUEST when a \
            member of staff has to take this over"
                  },
                  "reason": {
                    "type": "string",
                    "description": "One short sentence a colleague will read later"
                  }
                },
                "required": ["mode", "reason"]
              }
            }""",
            """
            {
              "name": "end_call",
              "description": "Hang up. Only after the caller has said goodbye or there is \
            nothing left to do for them.",
              "parameters": {
                "type": "object",
                "properties": {
                  "reason": {
                    "type": "string",
                    "description": "One short sentence saying why the call ended"
                  }
                },
                "required": ["reason"]
              }
            }""",
            """
            {
              "name": "lookup_client",
              "description": "Check whether the caller is a customer on the records, before \
            saying anything about an order or a job already in progress. Either give the full \
            phone number they read out, or give their customer code together with the last \
            four digits of the number we hold — a code on its own is not enough to identify \
            anybody. This answers only yes or no; it never tells you their name.",
              "parameters": {
                "type": "object",
                "properties": {
                  "clientCode": {
                    "type": "string",
                    "description": "The code the caller gave, such as C001. Must be sent with \
            phoneLastFour."
                  },
                  "phoneLastFour": {
                    "type": "string",
                    "description": "The last four digits of the phone number the caller says we \
            have for them"
                  },
                  "phone": {
                    "type": "string",
                    "description": "The caller's full phone number, in any format. Enough on \
            its own."
                  }
                }
              }
            }""",
            """
            {
              "name": "create_client",
              "description": "Write a new customer down, once they have given you their name \
            and a number to reach them on. Once per call, and only for somebody the records \
            do not already have.",
              "parameters": {
                "type": "object",
                "properties": {
                  "name": {"type": "string", "description": "What they said their name is"},
                  "phone": {"type": "string", "description": "A number they can be reached on"},
                  "request": {
                    "type": "string",
                    "description": "One sentence saying what they called about"
                  }
                },
                "required": ["name", "request"]
              }
            }""",
            """
            {
              "name": "log_request",
              "description": "Add a line to the caller's record saying what they needed on this \
            call, so whoever picks it up next can read it.",
              "parameters": {
                "type": "object",
                "properties": {
                  "summary": {
                    "type": "string",
                    "description": "One sentence, written for a colleague to read later"
                  }
                },
                "required": ["summary"]
              }
            }""",
            """
            {
              "name": "escalate_to_human",
              "description": "Hand this call to a member of staff, who is emailed a summary of \
            it when the call ends. Use it for a refund or discount you cannot approve, a \
            complaint, a legal question, or a caller who asks for a person. Stay on the line \
            afterwards and take down whatever that person will need.",
              "parameters": {
                "type": "object",
                "properties": {
                  "reason": {
                    "type": "string",
                    "description": "One short sentence saying why this needs a person"
                  },
                  "details": {
                    "type": "string",
                    "description": "Anything the caller gave that the colleague will need, such \
            as an order number or when to call back"
                  }
                },
                "required": ["reason"]
              }
            }""");

    /** Every tool's schema as JSON text, in the order the model reads them. */
    public List<String> schemas() {
        return SCHEMAS;
    }
}
