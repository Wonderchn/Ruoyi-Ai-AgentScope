"""Generate an exact-size valid one-page PDF; an embedded file supplies byte load.
This tests upload/parser byte limits, not a fifty-megabyte text/page workload.
Compatible with the owned VM's standard Python; no packages or network access.
"""
from __future__ import print_function
import sys

def build(padding):
    content = b"BT /F1 16 Tf 50 750 Td (P2-MARKER-XYZZY Marker-Beta-2026) Tj ET"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R /Names << /EmbeddedFiles << /Names [(fixture.bin) 7 0 R] >> >> >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(content)).encode('ascii') + b" >>\nstream\n" + content + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Type /EmbeddedFile /Length " + str(padding).encode('ascii') + b" >>\nstream\n" + b"0" * padding + b"\nendstream",
        b"<< /Type /Filespec /F (fixture.bin) /EF << /F 6 0 R >> >>"
    ]
    chunks = [b"%PDF-1.7\n"]
    offsets = [0]
    position = len(chunks[0])
    for number, obj in enumerate(objects, 1):
        offsets.append(position)
        item = str(number).encode('ascii') + b" 0 obj\n" + obj + b"\nendobj\n"
        chunks.append(item)
        position += len(item)
    xref = b"xref\n0 8\n0000000000 65535 f \n"
    xref += b"".join(("%010d 00000 n \n" % off).encode('ascii') for off in offsets[1:])
    chunks.append(xref + b"trailer\n<< /Size 8 /Root 1 0 R >>\nstartxref\n" + str(position).encode('ascii') + b"\n%%EOF\n")
    return b"".join(chunks)

target = int(sys.argv[2])
padding = target - 1200
for attempt in range(10):
    result = build(padding)
    if len(result) == target:
        with open(sys.argv[1], 'wb') as output:
            output.write(result)
        print("valid PDF bytes=%d pages=1 attachment-padding=%d" % (target, padding))
        break
    padding += target - len(result)
else:
    raise RuntimeError("could not reach exact fixture size")
