from __future__ import print_function
import sys
pages=int(sys.argv[2]); objects=[b'',b'']
font=3;objects.append(b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
kids=[]
for i in range(pages):
 page=len(objects)+1;content=page+1;kids.append(page)
 text=('BT /F1 16 Tf 50 740 Td (Owned restart fixture page %d P2-MARKER-XYZZY) Tj ET' % (i+1)).encode('ascii')
 objects.append(('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents %d 0 R >>' % content).encode('ascii'))
 objects.append(('<< /Length %d >>\nstream\n' % len(text)).encode('ascii')+text+b'\nendstream')
objects[0]=b'<< /Type /Catalog /Pages 2 0 R >>'
objects[1]=('<< /Type /Pages /Count %d /Kids [%s] >>' % (pages,' '.join('%d 0 R'%n for n in kids))).encode('ascii')
marker=sys.argv[3] if len(sys.argv)>3 else 'baseline'
if not all(c.isalnum() or c in '-_' for c in marker): raise ValueError('invalid fixture marker')
chunks=[('%PDF-1.7\n%'+marker+'\n').encode('ascii')]; offsets=[0];position=len(chunks[0])
for n,obj in enumerate(objects,1):
 offsets.append(position);chunk=('%d 0 obj\n'%n).encode('ascii')+obj+b'\nendobj\n';chunks.append(chunk);position+=len(chunk)
xref=('xref\n0 %d\n0000000000 65535 f \n'%(len(objects)+1)).encode('ascii')+b''.join(('%010d 00000 n \n'%n).encode('ascii') for n in offsets[1:])
chunks.append(xref+('trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n'%(len(objects)+1,position)).encode('ascii'))
with open(sys.argv[1],'wb') as f: f.write(b''.join(chunks))
print('owned PDF pages=%d bytes=%d'%(pages,len(b''.join(chunks))))
