import { watchPresenter } from "@/lib/server/presenterFeed";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

/** Server-Sent Events: one `data:` frame with the full snapshot on every Firestore change. */
export async function GET(request: Request) {
  const encoder = new TextEncoder();
  let stop = () => {};
  const stream = new ReadableStream({
    start(controller) {
      const send = (chunk: string) => {
        try {
          controller.enqueue(encoder.encode(chunk));
        } catch {
          stop();
        }
      };
      const unsubscribe = watchPresenter(
        (snapshot) => send(`data: ${JSON.stringify(snapshot)}\n\n`),
        (error) => send(`event: feed-error\ndata: ${JSON.stringify({ message: error.message })}\n\n`),
      );
      const heartbeat = setInterval(() => send(`: latido\n\n`), 15000);
      stop = () => {
        clearInterval(heartbeat);
        unsubscribe();
      };
      request.signal.addEventListener("abort", () => {
        stop();
        try {
          controller.close();
        } catch {}
      });
    },
    cancel() {
      stop();
    },
  });
  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    },
  });
}
