#!/usr/bin/env python3
"""Gate 1 harness — does Gemma handle tool-calling well enough for the agent loop?

Fires deterministic tool-calling scenarios at any OpenAI-compatible endpoint and
scores them programmatically (no LLM judge). Run it against Fireworks Gemma today,
against the AMD pod vLLM endpoint tomorrow — same script, same evidence format.

Usage:
    pip install openai
    export GATE1_API_KEY=...
    python gate1_toolcall_test.py \
        --base-url https://api.fireworks.ai/inference/v1 \
        --model accounts/fireworks/models/gemma-4-26b-a4b-it \
        --runs 5

Pass criteria (Gate 1, see docs/planning): >= 80% scenario pass rate over N runs,
zero malformed tool-call JSON.
"""

import argparse
import json
import os
import sys
import time

from openai import OpenAI

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "Get current weather for a city.",
            "parameters": {
                "type": "object",
                "properties": {
                    "city": {"type": "string"},
                    "unit": {"type": "string", "enum": ["celsius", "fahrenheit"]},
                },
                "required": ["city"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "create_task",
            "description": "Create a task in the user's task list.",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "due_date": {"type": "string", "description": "ISO date YYYY-MM-DD"},
                },
                "required": ["title"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "send_notification",
            "description": "Send a push notification to one of the user's devices.",
            "parameters": {
                "type": "object",
                "properties": {
                    "device": {"type": "string", "enum": ["phone", "watch", "desktop"]},
                    "message": {"type": "string"},
                },
                "required": ["device", "message"],
            },
        },
    },
]


def _tool_calls(msg):
    return list(msg.tool_calls or [])


def scenario_single_call(client, model):
    """Must pick get_weather and extract the city."""
    r = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": "What's the weather in Campinas right now?"}],
        tools=TOOLS,
        temperature=0,
    )
    calls = _tool_calls(r.choices[0].message)
    if len(calls) != 1 or calls[0].function.name != "get_weather":
        return False, f"expected 1 get_weather call, got {[c.function.name for c in calls]}"
    args = json.loads(calls[0].function.arguments)
    ok = "campinas" in args.get("city", "").lower()
    return ok, f"args={args}"


def scenario_enum_args(client, model):
    """Must respect the enum: device must be 'watch'."""
    r = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": "Buzz my watch saying the build finished."}],
        tools=TOOLS,
        temperature=0,
    )
    calls = _tool_calls(r.choices[0].message)
    if len(calls) != 1 or calls[0].function.name != "send_notification":
        return False, f"expected send_notification, got {[c.function.name for c in calls]}"
    args = json.loads(calls[0].function.arguments)
    return args.get("device") == "watch", f"args={args}"


def scenario_no_tool(client, model):
    """Must answer directly — calling any tool here is a hallucinated call."""
    r = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": "In one sentence, what is a mixture-of-experts model?"}],
        tools=TOOLS,
        temperature=0,
    )
    msg = r.choices[0].message
    calls = _tool_calls(msg)
    return len(calls) == 0 and bool(msg.content), f"calls={[c.function.name for c in calls]}"


def scenario_multi_turn(client, model):
    """Tool result comes back; model must use it and finish with a text answer."""
    messages = [{"role": "user", "content": "Check the weather in Campinas and if it's raining create a task 'take umbrella' for today."}]
    r = client.chat.completions.create(model=model, messages=messages, tools=TOOLS, temperature=0)
    msg = r.choices[0].message
    calls = _tool_calls(msg)
    if not calls or calls[0].function.name != "get_weather":
        return False, "did not start with get_weather"
    messages.append(msg.model_dump(exclude_none=True))
    messages.append({
        "role": "tool",
        "tool_call_id": calls[0].id,
        "content": json.dumps({"city": "Campinas", "condition": "rain", "temp_c": 19}),
    })
    r2 = client.chat.completions.create(model=model, messages=messages, tools=TOOLS, temperature=0)
    msg2 = r2.choices[0].message
    calls2 = _tool_calls(msg2)
    ok = len(calls2) == 1 and calls2[0].function.name == "create_task" \
        and "umbrella" in json.loads(calls2[0].function.arguments).get("title", "").lower()
    return ok, f"second step calls={[c.function.name for c in calls2]}"


SCENARIOS = [
    ("single_call", scenario_single_call),
    ("enum_args", scenario_enum_args),
    ("no_tool_needed", scenario_no_tool),
    ("multi_turn_chain", scenario_multi_turn),
]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", required=True)
    p.add_argument("--model", required=True)
    p.add_argument("--api-key", default=os.environ.get("GATE1_API_KEY", ""))
    p.add_argument("--runs", type=int, default=3)
    p.add_argument("--threshold", type=float, default=0.8)
    args = p.parse_args()

    client = OpenAI(base_url=args.base_url, api_key=args.api_key or "none")
    results, malformed, latencies = {}, 0, []

    for name, fn in SCENARIOS:
        passes = 0
        for i in range(args.runs):
            t0 = time.time()
            try:
                ok, detail = fn(client, args.model)
            except json.JSONDecodeError as e:
                malformed += 1
                ok, detail = False, f"MALFORMED JSON: {e}"
            except Exception as e:
                ok, detail = False, f"ERROR: {type(e).__name__}: {e}"
            latencies.append(time.time() - t0)
            passes += ok
            print(f"  {name} run {i+1}/{args.runs}: {'PASS' if ok else 'FAIL'} ({detail})")
        results[name] = passes / args.runs

    total = sum(results.values()) / len(results)
    print("\n=== GATE 1 REPORT ===")
    print(f"endpoint: {args.base_url}  model: {args.model}  runs/scenario: {args.runs}")
    for name, rate in results.items():
        print(f"  {name:20s} {rate:.0%}")
    print(f"overall: {total:.0%}   malformed tool JSON: {malformed}")
    print(f"latency: avg {sum(latencies)/len(latencies):.1f}s  max {max(latencies):.1f}s")
    verdict = total >= args.threshold and malformed == 0
    print(f"VERDICT: {'GREEN — Gemma drives the agent loop' if verdict else 'RED — invoke Plan B (multi-model)'}")
    sys.exit(0 if verdict else 1)


if __name__ == "__main__":
    main()
