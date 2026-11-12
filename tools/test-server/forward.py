#!/usr/bin/env python3
"""A second door to the test server, to take the server away from one emulator only.

forward.py [listen-port] [server-port] [kbit/s]      default 8097 -> 8096, as fast as it goes

Point one emulator's app at http://10.0.2.2:<listen-port>. Stop this script (Ctrl-C, or kill it)
and that emulator has lost the server, with its network still up: open connections are cut and
new ones are refused. Start it again and the server is back. The server itself and every other
emulator, on the server's own port, notice nothing.

With a third argument the server's answers are slowed to that many kbit/s per connection. A player
buffers a whole test file within a second otherwise, and then plays on without the server: give a
rate just above the file's bitrate (the 12 minute files are about 430 kbit/s) to cut a stream that
is still loading.
"""
import socket
import sys
import threading
import time

listen_port = int(sys.argv[1]) if len(sys.argv) > 1 else 8097
server_port = int(sys.argv[2]) if len(sys.argv) > 2 else 8096
bytes_per_second = int(sys.argv[3]) * 125 if len(sys.argv) > 3 else 0


def pump(src, dst, rate=0):
    try:
        while data := src.recv(8192 if rate else 65536):
            dst.sendall(data)
            if rate:
                time.sleep(len(data) / rate)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            s.close()


def serve(client):
    try:
        server = socket.create_connection(("127.0.0.1", server_port), timeout=10)
        server.settimeout(None)
    except OSError:
        client.close()
        return
    threading.Thread(target=pump, args=(client, server), daemon=True).start()
    pump(server, client, bytes_per_second)


listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("0.0.0.0", listen_port))
listener.listen(64)
limit = f", {bytes_per_second // 125} kbit/s per connection" if bytes_per_second else ""
print(f"forwarding :{listen_port} -> 127.0.0.1:{server_port}{limit}", flush=True)
try:
    while True:
        conn, _ = listener.accept()
        threading.Thread(target=serve, args=(conn,), daemon=True).start()
except KeyboardInterrupt:
    pass
