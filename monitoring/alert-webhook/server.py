import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class AlertHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)

        try:
            payload = json.loads(body)
            print(json.dumps(payload, indent=2), flush=True)
        except json.JSONDecodeError:
            print(body.decode("utf-8"), flush=True)

        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"OK")


if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", 8080), AlertHandler)
    print("Alert webhook listening on port 8080", flush=True)
    server.serve_forever()
