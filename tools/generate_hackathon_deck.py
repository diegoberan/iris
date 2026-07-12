from pathlib import Path

from reportlab.lib.colors import Color, HexColor
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.pdfgen import canvas


ROOT = Path(__file__).resolve().parents[1]
SUBMISSION_OUT = ROOT / "docs" / "deck" / "iris-amd-hackathon-deck.pdf"
TECHNICAL_OUT = ROOT / "docs" / "deck" / "iris-amd-hackathon-technical-appendix.pdf"
LOGO = ROOT / "site" / "static" / "img" / "iris-mark.png"
PAGE = (13.333 * 72, 7.5 * 72)

BG = HexColor("#0E0E0E")
CARD = HexColor("#161616")
CARD_2 = HexColor("#1C1C1C")
FG = HexColor("#F0F0ED")
MUTED = HexColor("#AAAAA4")
DIM = HexColor("#777771")
AMBER = HexColor("#E0A03A")
BLUE = HexColor("#4C6E82")
GREEN = HexColor("#8CAB7A")
BORDER = Color(76 / 255, 110 / 255, 130 / 255, alpha=0.6)
MONO = "Courier"
MONO_BOLD = "Courier-Bold"


def wrap(value, font, size, width):
    words, line, lines = value.split(), "", []
    for word in words:
        candidate = f"{line} {word}".strip()
        if stringWidth(candidate, font, size) <= width or not line:
            line = candidate
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def paragraph(c, value, x, y, size=12, color=MUTED, font="Helvetica", max_width=400, leading=None):
    leading = leading or size * 1.35
    c.setFillColor(color)
    c.setFont(font, size)
    lines = wrap(value, font, size, max_width)
    for index, line in enumerate(lines):
        c.drawString(x, y - index * leading, line)
    return y - len(lines) * leading


def shell(c, number, label):
    w, h = PAGE
    c.setFillColor(BG)
    c.rect(0, 0, w, h, fill=1, stroke=0)
    c.setStrokeColor(BORDER)
    c.line(42, h - 42, w - 42, h - 42)
    c.setFillColor(AMBER)
    c.setFont("Helvetica-Bold", 9)
    c.drawString(42, h - 28, "IRIS / AMD DEVELOPER HACKATHON ACT II")
    c.setFillColor(DIM)
    c.setFont("Helvetica", 9)
    c.drawRightString(w - 42, h - 28, f"{number:02d}  {label.upper()}")
    c.setFillColor(DIM)
    c.setFont("Helvetica", 8)
    c.drawRightString(w - 42, 22, "iris.dberan.dev  |  github.com/diegoberan/iris")


def title(c, heading, subheading=None):
    c.setFillColor(FG)
    c.setFont("Helvetica", 27)
    c.drawString(42, 455, heading)
    if subheading:
        paragraph(c, subheading, 45, 416, 12.5, MUTED, max_width=820, leading=18)


def card(c, x, y, width, height, heading, body, tag=None, tag_color=AMBER, body_size=10.2):
    c.setFillColor(CARD)
    c.setStrokeColor(BORDER)
    c.roundRect(x, y, width, height, 13, fill=1, stroke=1)
    top = y + height - 24
    if tag:
        c.setFillColor(tag_color)
        c.setFont("Helvetica-Bold", 8)
        c.drawString(x + 18, top, tag.upper())
        top -= 23
    c.setFillColor(FG)
    c.setFont("Helvetica-Bold", 15)
    c.drawString(x + 18, top, heading)
    paragraph(c, body, x + 18, top - 25, body_size, MUTED, max_width=width - 36, leading=body_size * 1.4)


def code_box(c, x, y, width, height, lines, accent=BLUE):
    c.setFillColor(HexColor("#111111"))
    c.setStrokeColor(Color(accent.red, accent.green, accent.blue, alpha=0.7))
    c.roundRect(x, y, width, height, 11, fill=1, stroke=1)
    c.setFillColor(accent)
    c.setFont(MONO_BOLD, 8.5)
    c.drawString(x + 16, y + height - 21, "WIRE FORMAT")
    c.setFillColor(FG)
    c.setFont(MONO, 8.5)
    baseline = y + height - 43
    for line in lines:
        c.drawString(x + 16, baseline, line)
        baseline -= 13


def arrow(c, x1, y1, x2, y2, color=AMBER):
    c.setStrokeColor(color)
    c.setFillColor(color)
    c.setLineWidth(1.4)
    c.line(x1, y1, x2, y2)
    c.circle(x2, y2, 3.2, fill=1, stroke=0)


def slide_1(c):
    shell(c, 1, "Technical deck")
    if LOGO.exists():
        c.drawImage(str(LOGO), 44, 335, width=66, height=66, mask="auto")
    c.setFillColor(AMBER)
    c.setFont("Helvetica-Bold", 11)
    c.drawString(44, 316, "TECHNICAL ARCHITECTURE / TRACK 3")
    c.setFillColor(FG)
    c.setFont("Helvetica", 37)
    c.drawString(44, 244, "Iris: one Brain, multiple Bodies.")
    paragraph(c, "A capability protocol that lets a persistent Hermes agent discover, route to, and recover across the devices a user already owns.", 47, 196, 15, MUTED, max_width=595, leading=22)
    card(c, 670, 151, 230, 210, "The core idea", "The cloud Brain holds memory and orchestration. Devices join as Bodies and contribute live capabilities: local inference, voice, notifications, location, and native interaction.", "Implementation")
    c.setFillColor(DIM)
    c.setFont("Helvetica", 10)
    c.drawString(44, 70, "This deck explains the system below the interface: protocol, transport, nodes, routing, compute, and tenant isolation.")


def slide_2(c):
    shell(c, 2, "Positioning")
    title(c, "MCP connects AI applications to external systems.", "Iris gives a persistent agent a live capability fabric across the devices a user already owns.")
    card(c, 45, 180, 250, 155, "MCP", "A standard way for an AI application to connect to tools, data sources, and workflows.", "External systems", BLUE)
    card(c, 355, 180, 250, 155, "Hermes", "The agent engine: persistent context, memory, tools, execution policy, and orchestration.", "Agent runtime", AMBER)
    card(c, 665, 180, 250, 155, "Iris", "The product layer: a capability protocol and device runtime that lets Hermes use live user-owned hardware.", "Device fabric", GREEN)
    arrow(c, 295, 257, 355, 257, BLUE)
    arrow(c, 605, 257, 665, 257, AMBER)
    c.setFillColor(AMBER)
    c.setFont("Helvetica-Bold", 13)
    c.drawString(45, 112, "Hermes is the agent engine. Iris is the persistent multi-device product layer.")


def slide_3(c):
    shell(c, 3, "System topology")
    title(c, "The Brain is persistent. Bodies are opportunistic.", "The data plane keeps the agent reachable 24/7 while the capability plane expands or contracts as devices connect.")
    card(c, 45, 145, 180, 180, "Desktop Body", "Electron desktop detects local services and announces GPU LLM and F5-TTS voice endpoints.", "Local machine")
    card(c, 267, 145, 180, 180, "Android Body", "Kotlin foreground service maintains a Ktor WebSocket. It announces notification and location.", "Mobile")
    card(c, 489, 145, 180, 180, "Wear OS client", "Native watch client connects to the Hermes gateway for voice, TTS, sessions, and paired-phone credential sync.", "Wrist")
    card(c, 711, 145, 200, 180, "Hermes Brain", "Gateway WebSocket, live registry, orchestrator, memory, tools, and model route selection.", "Cloud brain")
    arrow(c, 225, 235, 267, 235)
    arrow(c, 447, 235, 489, 235)
    arrow(c, 669, 235, 711, 235)
    c.setFillColor(DIM)
    c.setFont("Helvetica", 9.5)
    c.drawString(45, 105, "Each Body owns its hardware and permissions. The Brain owns intent, context, tool use, and execution policy.")


def slide_4(c):
    shell(c, 4, "Protocol")
    title(c, "A small protocol turns devices into an agent capability fabric.", "Bodies announce what is available after connection, then receive actions over the same authenticated gateway WebSocket.")
    code_box(c, 45, 142, 410, 203, [
        "hermes.capabilities.announce",
        "{",
        "  device: 'android',",
        "  notification: { available: true },",
        "  location: { available: true }",
        "}",
    ])
    code_box(c, 500, 142, 410, 203, [
        "notification.send.request",
        "{ title, body, request_id }",
        "",
        "device.action.response",
        "{ request_id, success, device }",
    ], GREEN)
    arrow(c, 455, 244, 500, 244)
    c.setFillColor(FG)
    c.setFont("Helvetica-Bold", 14)
    c.drawString(45, 102, "Two patterns, one transport")
    paragraph(c, "Push actions: notification.send. Pull actions: location.current returns latitude, longitude, and accuracy in the correlated response envelope.", 45, 78, 10.5, MUTED, max_width=780, leading=15)


def slide_5(c):
    shell(c, 5, "Registry and routing")
    title(c, "Presence is a routing signal, not a settings screen.", "The Capability Registry is updated by announce and disconnect events. The Orchestrator receives a live provider chain for every capability.")
    card(c, 45, 160, 195, 170, "1. Register", "An announce payload is namespaced by capability and bound to a device type. Registry state changes without redeploying the Brain.", "On connect")
    card(c, 276, 160, 195, 170, "2. Select", "For each action, the Orchestrator chooses the best provider in its configured, registry-aware chain.", "Per action")
    card(c, 507, 160, 195, 170, "3. Correlate", "A request_id follows the action from Brain to Body and back. The first valid response completes the call.", "On wire")
    card(c, 738, 160, 175, 170, "4. Recover", "Disconnect removes the provider. Failure or timeout advances to the next route for a future turn.", "Failover")
    c.setFillColor(AMBER)
    c.setFont(MONO_BOLD, 10)
    c.drawString(46, 105, "registry + config -> ordered providers -> action -> correlated result")


def slide_6(c):
    shell(c, 6, "Connection and trust")
    title(c, "The Node never starts with an open socket.", "Authentication happens before the persistent connection. The gateway hands the Body a single-use WebSocket ticket after login.")
    steps = [
        ("01", "Login", "POST /auth/password-login with the selected provider."),
        ("02", "Ticket", "POST /api/auth/ws-ticket returns a short-lived, single-use ticket."),
        ("03", "Socket", "wss://gateway/api/ws?ticket= establishes the authenticated session."),
        ("04", "Announce", "The Body reports its current capabilities after every reconnect."),
    ]
    x = 45
    for no, heading, body in steps:
        c.setFillColor(CARD)
        c.setStrokeColor(BORDER)
        c.roundRect(x, 175, 202, 155, 13, fill=1, stroke=1)
        c.setFillColor(AMBER)
        c.setFont(MONO_BOLD, 11)
        c.drawString(x + 17, 300, no)
        c.setFillColor(FG)
        c.setFont("Helvetica-Bold", 16)
        c.drawString(x + 17, 270, heading)
        paragraph(c, body, x + 17, 242, 10, MUTED, max_width=167, leading=14)
        x += 218
    c.setFillColor(DIM)
    c.setFont("Helvetica", 9.5)
    c.drawString(45, 122, "The ticketed socket keeps the action channel authenticated without placing a long-lived credential in every message.")


def slide_7(c):
    shell(c, 7, "Android Body")
    title(c, "Android exposes real-world context only when the Brain needs it.", "The app is Kotlin-based, uses Ktor for the gateway WebSocket, and maintains the Body with a foreground service.")
    card(c, 45, 170, 205, 160, "Lifecycle", "Login, fetch a WebSocket ticket, connect, announce, and repeat announce on reconnect. It is designed for a persistent mobile session.", "Kotlin + Ktor")
    card(c, 280, 170, 205, 160, "notification.send", "The Brain emits a request. Android posts a high-priority OS notification then answers device.action.response.", "Push")
    card(c, 515, 170, 205, 160, "location.current", "Android uses LocationManager.getCurrentLocation and returns a live GPS fix only after the Brain requests it.", "Pull")
    card(c, 750, 170, 160, 160, "Validated", "Galaxy S20 FE, production gateway, real notification delivery and live GPS response.", "Hardware run", GREEN)
    c.setFillColor(AMBER)
    c.setFont("Helvetica-Bold", 11)
    c.drawString(45, 112, "Important: location is not streamed to the cloud. It is requested on demand through the capability action.")


def slide_8(c):
    shell(c, 8, "Desktop Body")
    title(c, "The desktop contributes private compute and a voice persona.", "The Electron desktop watches local services, checks their health, and announces available endpoints to the Brain.")
    code_box(c, 45, 148, 385, 195, [
        "device: 'desktop'",
        "speech: {",
        "  available: true,",
        "  localUrl: '127.0.0.1:8123',",
        "  voices: ['dot']",
        "}",
        "llm: { localUrl: '127.0.0.1:1234/v1' }",
    ])
    card(c, 475, 208, 205, 135, "Local LLM", "Gemma runs in LM Studio on the Radeon RX 9060 XT. The local endpoint is OpenAI-compatible.", "Inference")
    card(c, 710, 208, 200, 135, "Voice", "F5-TTS runs beside the model and exposes Dot, the secretary persona, as the speech provider.", "Synthesis")
    c.setFillColor(DIM)
    c.setFont("Helvetica", 10)
    c.drawString(45, 108, "The Brain can use the machine when it is online. When it is not, the routing chain advances instead of losing the assistant.")


def slide_9(c):
    shell(c, 9, "Wear OS client")
    title(c, "A native wrist client for the same Brain.", "The current Wear OS implementation prioritizes direct interaction: short voice exchanges, spoken responses, sessions, and seamless sign-in from the paired phone.")
    card(c, 46, 172, 205, 160, "Gateway client", "Ktor client mirrors the phone connection model: auth, ticket, and a persistent WebSocket to Hermes.", "Transport")
    card(c, 281, 172, 205, 160, "Voice UX", "Native speech-to-text and TTS make the watch a low-friction conversational surface rather than a notification mirror.", "Interaction")
    card(c, 516, 172, 205, 160, "Data Layer", "Paired-phone credential synchronization reduces setup friction while preserving a direct watch-to-Brain session.", "Pairing")
    card(c, 751, 172, 160, 160, "Scope today", "The watch is a direct client. Sensor and heart-rate capabilities are a planned capability-protocol extension.", "Honest status", BLUE)


def slide_10(c):
    shell(c, 10, "AMD compute fabric")
    title(c, "One inference contract across local, cloud, and fallback routes.", "Every route is OpenAI-compatible, so Hermes can change provider without changing the agent interface or conversation state.")
    card(c, 45, 175, 205, 160, "Local AMD", "Radeon RX 9060 XT 16 GB runs Gemma through LM Studio. First choice when the desktop is live and privacy matters.", "Desktop tier")
    card(c, 280, 175, 205, 160, "AMD cloud", "The live pod uses an MI300 and vLLM. Gemma is served at /v1 with GPU memory utilization and long-context configuration.", "Current runtime")
    card(c, 515, 175, 205, 160, "AMD validation", "Radeon PRO W7900 was tested during the hackathon with Gemma Q4 served through llama.cpp.", "Workstation")
    card(c, 750, 175, 160, 160, "Fallback", "Fireworks AI API is wired as the final external resilience route.", "Last resort")
    c.setFillColor(AMBER)
    c.setFont(MONO_BOLD, 10)
    c.drawString(46, 112, "RX 9060 XT / LM Studio -> MI300 / vLLM -> Fireworks API")


def slide_11(c):
    shell(c, 11, "Inference handoff")
    title(c, "The Brain keeps the conversation; compute can move.", "Routing is about available capability and execution policy, not moving the user's agent identity from one model host to another.")
    card(c, 45, 195, 185, 135, "1. Intent", "Hermes receives the turn with its memory, tools, and conversation context.", "Brain")
    card(c, 270, 195, 185, 135, "2. Resolve", "The Orchestrator observes live providers and selects a model route.", "Policy")
    card(c, 495, 195, 185, 135, "3. Execute", "The chosen OpenAI-compatible endpoint generates the response or tool call.", "Compute")
    card(c, 720, 195, 185, 135, "4. Recover", "If the route disappears, the next turn uses the next available provider.", "Continuity")
    for a, b in ((230, 270), (455, 495), (680, 720)):
        arrow(c, a, 262, b, 262)
    paragraph(c, "This separation is what lets Iris preserve an always-on personal assistant while still harvesting the user's own GPU when it becomes available.", 45, 125, 11, MUTED, max_width=820, leading=16)


def slide_12(c):
    shell(c, 12, "SaaS implementation")
    title(c, "The product path is implemented, not mocked.", "Iris combines sign-up, Stripe checkout, automated provisioning, and isolated Hermes environments so the architecture can be delivered as a service.")
    steps = [
        ("Account", "Django account and console establish the customer identity."),
        ("Checkout", "Stripe supports managed-compute and BYOK plan paths."),
        ("Provision", "Ubuntu automation creates the tenant environment."),
        ("Operate", "The new Brain receives its own services and can attach Bodies."),
    ]
    x = 45
    for index, (heading, body) in enumerate(steps, 1):
        card(c, x, 177, 202, 160, heading, body, f"0{index}")
        x += 218
    c.setFillColor(FG)
    c.setFont("Helvetica-Bold", 12)
    c.drawString(45, 118, "Isolation boundary per tenant")
    paragraph(c, "Unique Unix identity, Hermes runtime, memory, credentials, environment variables, and services. Tenant setup also uses a per-tenant dashboard secret for server-to-server trust.", 45, 94, 10.5, MUTED, max_width=850, leading=15)


def slide_13(c):
    shell(c, 13, "Integration surface")
    title(c, "Capabilities become tools without modifying the agent core.", "The iris-body skill invokes the Orchestrator through loopback HTTP. The transport and device protocol remain behind a narrow integration surface.")
    code_box(c, 45, 155, 390, 185, [
        "POST /iris/notify",
        "  -> notification.send.request",
        "  -> device.action.response",
        "  -> 200 { delivered: true }",
        "",
        "GET /iris/location",
        "  -> location.current.request",
        "  -> 200 { latitude, longitude, accuracy }",
    ], GREEN)
    card(c, 485, 205, 195, 135, "Agent contract", "The agent calls a simple local HTTP endpoint. It does not need to know whether the Body is a phone today or another device tomorrow.", "Decoupling")
    card(c, 715, 205, 195, 135, "Failure contract", "If no Body announced the capability, endpoints return HTTP 503 with a clear reason instead of pretending success.", "Operational")
    c.setFillColor(DIM)
    c.setFont("Helvetica", 10)
    c.drawString(45, 112, "The same pattern can extend to future Bodies and new capability types without creating a custom agent integration for every device.")


def slide_14(c):
    shell(c, 14, "Proof and next steps")
    title(c, "What is running, what is deliberately next.", "The implementation is centered on a working end-to-end vertical slice, then expands the capability vocabulary with the same protocol.")
    card(c, 45, 165, 270, 175, "Working now", "Production Brain, authenticated WebSocket, registry and orchestration flow, Android notification/location, desktop local services, MI300 vLLM runtime, checkout, and tenant provisioning.", "Implemented", GREEN)
    card(c, 345, 165, 270, 175, "Validated", "Android ran against the production gateway on a Galaxy S20 FE. Notification delivery and a live GPS fix were returned end-to-end through the agent workflow.", "Evidence", AMBER)
    card(c, 645, 165, 270, 175, "Next protocol extensions", "Stable per-Body identities, versioned capability schemas, granular permissions, streaming results, and Watch sensor capabilities.", "Roadmap", BLUE)
    c.setFillColor(AMBER)
    c.setFont("Helvetica-Bold", 13)
    c.drawString(45, 104, "Iris is the capability fabric through which Hermes can use the real world - while the user retains control of the devices behind it.")


def submission_1(c):
    shell(c, 1, "Iris")
    if LOGO.exists():
        c.drawImage(str(LOGO), 44, 322, width=92, height=92, mask="auto")
    c.setFillColor(AMBER)
    c.setFont("Helvetica-Bold", 11)
    c.drawString(44, 316, "AMD DEVELOPER HACKATHON ACT II / TRACK 3")
    c.setFillColor(FG)
    c.setFont("Helvetica", 37)
    c.drawString(44, 244, "One Brain. Multiple Bodies.")
    paragraph(c, "Iris is a persistent personal AI that can use the devices a person already owns: desktop GPU, voice, phone location, notifications, and native wrist interaction.", 47, 196, 15, MUTED, max_width=600, leading=22)
    card(c, 670, 151, 230, 210, "The difference", "Devices do not merely run an app. They announce live capabilities to the Brain. The Brain decides where work and actions should happen.", "Capability fabric")
    c.setFillColor(DIM)
    c.setFont("Helvetica", 10)
    c.drawString(44, 70, "Hermes is the agent engine. Iris is the persistent multi-device product layer.")


def submission_2(c):
    shell(c, 2, "Architecture")
    # No subheading here — the heading plus the converging diagram carry it,
    # and a subheading line would collide with the top row of body cards.
    title(c, "A live protocol connects the Brain to the user's world.")
    # Three bodies across the top — independent, each speaks to the Brain
    # directly (NOT a chain). Their arrows converge downward onto one Brain.
    bodies = [
        (60, "Desktop", "Local Gemma endpoint + F5-TTS voice.", "GPU + voice"),
        (365, "Android", "notification.send and location.current over a Ktor WebSocket.", "Action + context"),
        (670, "Wear OS", "Native voice, TTS, sessions, paired-phone sign-in.", "Interaction"),
    ]
    for x, heading, body, tag in bodies:
        card(c, x, 300, 230, 120, heading, body, tag, body_size=9.6)
    # Brain: centered below, visually emphasized (amber border, darker fill).
    bx, by, bw, bh = 300, 150, 360, 96
    c.setFillColor(CARD_2)
    c.setStrokeColor(AMBER)
    c.setLineWidth(2)
    c.roundRect(bx, by, bw, bh, 13, fill=1, stroke=1)
    c.setLineWidth(1)
    c.setFillColor(AMBER)
    c.setFont("Helvetica-Bold", 8)
    c.drawString(bx + 20, by + bh - 22, "PERSISTENT — ONE PER USER")
    c.setFillColor(FG)
    c.setFont("Helvetica-Bold", 17)
    c.drawString(bx + 20, by + bh - 45, "Hermes Brain")
    paragraph(c, "Registry + Orchestrator pick live providers, correlate responses, and handle presence and failover.", bx + 20, by + bh - 62, 9.6, MUTED, max_width=bw - 40, leading=13)
    # Converging arrows: each body's bottom-center down onto the Brain's top.
    for x in (60, 365, 670):
        arrow(c, x + 115, 300, bx + bw / 2, by + bh)
    c.setFillColor(AMBER)
    c.setFont(MONO_BOLD, 10)
    c.drawCentredString(480, 116, "announce  ->  live registry  ->  route action / inference  ->  correlated response")


def submission_3(c):
    shell(c, 3, "Capability protocol")
    title(c, "The technical novelty: presence-aware capability routing.", "A Body announces what is available. The Registry updates on connect or disconnect. The Orchestrator chooses a provider, sends a correlated request, and recovers between turns.")
    code_box(c, 45, 150, 390, 190, [
        "hermes.capabilities.announce",
        "{ device: 'android',",
        "  notification: { available: true },",
        "  location: { available: true } }",
        "",
        "disconnect -> capability leaves registry",
    ])
    code_box(c, 485, 150, 425, 190, [
        "notification.send.request",
        "{ title, body, request_id }",
        "",
        "device.action.response",
        "{ request_id, success, device }",
        "",
        "first valid response wins",
    ], GREEN)
    c.setFillColor(FG)
    c.setFont("Helvetica-Bold", 13)
    c.drawString(45, 110, "Validated on real Android hardware")
    paragraph(c, "A Galaxy S20 FE connected to the production gateway, received a real high-priority notification, and returned a live GPS fix on demand. If no Body is present, the integration returns a clear HTTP 503.", 45, 86, 10.5, MUTED, max_width=840, leading=15)


def submission_4(c):
    shell(c, 4, "AMD-hosted Gemma")
    title(c, "Your GPU first. AMD cloud when needed.", "Iris maintains one OpenAI-compatible inference contract while moving execution across available AMD compute routes.")
    card(c, 45, 185, 205, 155, "Radeon RX 9060 XT", "Gemma runs locally through LM Studio beside Dot's F5-TTS voice. This is the private first route when the desktop is online.", "AMD local")
    card(c, 280, 185, 205, 155, "AMD MI300 pod", "The current cloud runtime hosts Gemma through vLLM. It is the AMD-hosted route when local capacity is unavailable.", "AMD cloud")
    card(c, 515, 185, 205, 155, "Radeon PRO W7900", "Gemma Q4 was tested in the hackathon environment through llama.cpp, proving the route on AMD workstation hardware too.", "Validation")
    card(c, 750, 185, 160, 155, "Resilience", "Fireworks AI API is wired as the final external fallback route.", "Fallback")
    c.setFillColor(AMBER)
    c.setFont(MONO_BOLD, 10)
    c.drawString(46, 120, "RX 9060 XT / local Gemma -> MI300 / vLLM -> Fireworks API")
    c.setFillColor(DIM)
    c.setFont("Helvetica", 9.5)
    c.drawString(46, 94, "Conversation state stays in the Brain; if a route disappears, the next turn advances to the next provider.")


def submission_5(c):
    shell(c, 5, "Product readiness")
    title(c, "A SaaS delivery path already exists behind the demo.", "Iris is not a landing page around a prototype: account creation, checkout, automated provisioning, and tenant isolation are part of the product architecture.")
    steps = [
        ("01", "Account", "Django account and customer console."),
        ("02", "Plan", "Stripe checkout for managed compute or BYOK."),
        ("03", "Provision", "Dedicated Ubuntu tenant created automatically."),
        ("04", "Connect", "A private Brain receives its own Bodies."),
    ]
    x = 45
    for no, heading, body in steps:
        card(c, x, 180, 202, 154, heading, body, no)
        x += 218
    c.setFillColor(FG)
    c.setFont("Helvetica-Bold", 12)
    c.drawString(45, 118, "Per-tenant isolation")
    paragraph(c, "Every tenant receives a unique Unix identity, Hermes runtime, memory, credentials, environment, and services. The system has also been used in real multi-tenant deployments.", 45, 94, 10.5, MUTED, max_width=850, leading=15)


def submission_6(c):
    shell(c, 6, "Why Iris")
    title(c, "An agent that persists - and can act through the real world.", "Iris turns personal AI from an isolated chat session into an owned Brain with expandable, live capabilities.")
    card(c, 45, 175, 270, 160, "Original", "A presence-aware Capability Protocol + Orchestrator: devices announce what they can do; the Brain routes actions and inference across live providers.", "Core innovation")
    card(c, 345, 175, 270, 160, "Technically real", "Authenticated gateway, Registry, Android actions, desktop local services, AMD-hosted Gemma, and explicit failover behavior.", "Working system")
    card(c, 645, 175, 270, 160, "Ready to grow", "A user can create an account, pay, receive an isolated Brain, and attach their own devices and compute.", "Product path")
    c.setFillColor(AMBER)
    c.setFont("Helvetica-Bold", 14)
    c.drawString(45, 105, "Iris is the capability fabric through which Hermes interacts with the world.")
    c.setFillColor(DIM)
    c.setFont("Helvetica", 10)
    c.drawString(45, 78, "github.com/diegoberan/iris  |  iris.dberan.dev")


def generate_submission():
    SUBMISSION_OUT.parent.mkdir(parents=True, exist_ok=True)
    c = canvas.Canvas(str(SUBMISSION_OUT), pagesize=PAGE)
    c.setTitle("Iris - AMD Developer Hackathon ACT II")
    for slide in (submission_1, submission_2, submission_3, submission_4, submission_5, submission_6):
        slide(c)
        c.showPage()
    c.save()


def generate_technical_appendix():
    TECHNICAL_OUT.parent.mkdir(parents=True, exist_ok=True)
    c = canvas.Canvas(str(TECHNICAL_OUT), pagesize=PAGE)
    c.setTitle("Iris - Technical Architecture Appendix")
    for slide in (
        slide_1, slide_2, slide_3, slide_4, slide_5, slide_6, slide_7,
        slide_8, slide_9, slide_10, slide_11, slide_12, slide_13, slide_14,
    ):
        slide(c)
        c.showPage()
    c.save()
    print(TECHNICAL_OUT)


if __name__ == "__main__":
    generate_submission()
    generate_technical_appendix()
    print(SUBMISSION_OUT)
