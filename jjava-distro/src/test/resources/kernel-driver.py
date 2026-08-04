"""
Runs a sequence of notebook cells against the installed "java" kernel and prints a
machine-readable transcript of what the kernel sent back.

Usage: python kernel-driver.py <cell source> [<cell source> ...]

Each cell source is a separate argv element, so a cell may contain any number of lines.
Every captured value is printed as a single line, base64-encoded, so that multi-line
values and unrelated kernel log output can not be mistaken for the protocol:

    @@JJAVA@@|<cell number>|<field>|<base64 of the value>

Fields: "status" (always present, "ok" or "error"), "result", "display", "stdout",
"stderr", "error" (present only when non-empty).
"""

import base64
import re
import sys

from jupyter_client.manager import start_new_kernel

PREFIX = "@@JJAVA@@"
STARTUP_TIMEOUT = 120
MESSAGE_TIMEOUT = 180

# terminal control sequences the kernel uses to colorize JShell diagnostics
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def emit(cell_number, field, value):
    if value:
        encoded = base64.b64encode(value.encode("utf-8")).decode("ascii")
        print("%s|%d|%s|%s" % (PREFIX, cell_number, field, encoded), flush=True)


def execute(client, cell_number, source):
    msg_id = client.execute(source, allow_stdin=False)

    outputs = {"result": "", "display": "", "stdout": "", "stderr": "", "error": ""}
    while True:
        message = client.get_iopub_msg(timeout=MESSAGE_TIMEOUT)
        if message["parent_header"].get("msg_id") != msg_id:
            continue

        message_type = message["msg_type"]
        content = message["content"]
        if message_type == "status":
            if content["execution_state"] == "idle":
                break
        elif message_type == "stream":
            key = "stdout" if content["name"] == "stdout" else "stderr"
            outputs[key] += content["text"]
        elif message_type == "execute_result":
            outputs["result"] += content["data"].get("text/plain", "")
        elif message_type in ("display_data", "update_display_data"):
            outputs["display"] += content["data"].get("text/plain", "")
        elif message_type == "error":
            lines = ["%s: %s" % (content["ename"], content["evalue"])]
            lines.extend(content["traceback"])
            outputs["error"] += ANSI.sub("", "\n".join(lines))

    reply = client.get_shell_msg(timeout=MESSAGE_TIMEOUT)

    emit(cell_number, "status", reply["content"]["status"])
    for field, value in outputs.items():
        emit(cell_number, field, value)


def main(cells):
    manager, client = start_new_kernel(kernel_name="java", startup_timeout=STARTUP_TIMEOUT)
    try:
        for i, source in enumerate(cells):
            execute(client, i + 1, source)
    finally:
        client.stop_channels()
        manager.shutdown_kernel()


if __name__ == "__main__":
    main(sys.argv[1:])
