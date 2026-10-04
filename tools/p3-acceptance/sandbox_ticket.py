"""Owned test ticket provider: durable independent DB, exact key/hash and nonfinal absence."""
import hashlib,hmac,json,os,re,sqlite3,threading,time,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
DB=Path(os.environ["P3_SANDBOX_DB"])
TOKEN=os.environ["P3_SANDBOX_CREDENTIAL"]
NAMESPACE=os.environ["P3_SANDBOX_NAMESPACE"]
FAULTS=os.environ.get("P3_SANDBOX_FAULTS")=="true"
DB.parent.mkdir(parents=True,exist_ok=True)
with sqlite3.connect(DB) as db:
 db.executescript("CREATE TABLE IF NOT EXISTS tickets(namespace TEXT NOT NULL,operation_key TEXT NOT NULL,args_hash TEXT NOT NULL,args TEXT NOT NULL,external_id TEXT NOT NULL,requests INTEGER NOT NULL,writes INTEGER NOT NULL CHECK(writes=1),PRIMARY KEY(namespace,operation_key)); CREATE TABLE IF NOT EXISTS controls(namespace TEXT PRIMARY KEY,response_lost INTEGER NOT NULL DEFAULT 0,query_absent INTEGER NOT NULL DEFAULT 0,delay_ms INTEGER NOT NULL DEFAULT 0);")
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def reply(self,code,data):
  body=json.dumps(data,separators=(",",":"),ensure_ascii=False).encode()
  self.send_response(code);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(body)));self.end_headers()
  try:self.wfile.write(body)
  except (BrokenPipeError,ConnectionResetError):pass
 def allowed(self):
  return hmac.compare_digest(self.headers.get("Authorization",""),"Bearer "+TOKEN) and self.headers.get("X-Sandbox-Namespace")==NAMESPACE
 def do_GET(self):
  if not self.allowed():return self.reply(403,{"state":"DENIED"})
  if self.path=="/health":return self.reply(200,{"state":"READY","namespace":NAMESPACE})
  if not self.path.startswith("/tickets/"):return self.reply(404,{"state":"ABSENT","final":False})
  key=self.path[len("/tickets/"):]
  if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}",key):return self.reply(400,{"state":"INVALID"})
  with sqlite3.connect(DB,timeout=5) as db:
   db.execute("BEGIN IMMEDIATE")
   control=db.execute("SELECT query_absent FROM controls WHERE namespace=?",(NAMESPACE,)).fetchone()
   if control and control[0]>0:
    db.execute("UPDATE controls SET query_absent=query_absent-1 WHERE namespace=?",(NAMESPACE,));db.commit();return self.reply(200,{"state":"ABSENT","final":False,"operationKey":key})
   row=db.execute("SELECT args_hash,external_id,requests,writes FROM tickets WHERE namespace=? AND operation_key=?",(NAMESPACE,key)).fetchone();db.commit()
  if not row:return self.reply(200,{"state":"ABSENT","final":False,"operationKey":key})
  return self.reply(200,{"state":"FOUND","final":True,"operationKey":key,"argsHash":row[0],"externalId":row[1],"requests":row[2],"writes":row[3]})
 def do_POST(self):
  if not self.allowed():return self.reply(403,{"state":"DENIED"})
  length=int(self.headers.get("Content-Length","0"))
  if not 0<length<=8192:return self.reply(400,{"state":"INVALID"})
  try:body=json.loads(self.rfile.read(length))
  except Exception:return self.reply(400,{"state":"INVALID"})
  if self.path=="/control" and FAULTS:
   if set(body)-{"responseLost","queryAbsent","delayMs"}:return self.reply(400,{"state":"INVALID"})
   vals=[body.get(k,0) for k in ("responseLost","queryAbsent","delayMs")]
   if any(type(v) is not int or v<0 or v>30000 for v in vals):return self.reply(400,{"state":"INVALID"})
   with sqlite3.connect(DB) as db:db.execute("INSERT INTO controls(namespace,response_lost,query_absent,delay_ms) VALUES(?,?,?,?) ON CONFLICT(namespace) DO UPDATE SET response_lost=excluded.response_lost,query_absent=excluded.query_absent,delay_ms=excluded.delay_ms",(NAMESPACE,*vals))
   return self.reply(200,{"state":"ARMED"})
  if self.path!="/tickets" or set(body)!={"operationKey","argsHash","args"}:return self.reply(400,{"state":"INVALID"})
  key=body["operationKey"];args=body["args"];claimed=body["argsHash"]
  if not isinstance(key,str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}",key) or not isinstance(args,dict) or set(args)!={"title","details"}:return self.reply(400,{"state":"INVALID"})
  if any(not isinstance(args[k],str) or not args[k].strip() or len(args[k])>limit for k,limit in (("title",120),("details",1024))):return self.reply(400,{"state":"INVALID"})
  canonical=json.dumps(args,separators=(",",":"),sort_keys=True,ensure_ascii=False)
  digest=hashlib.sha256(canonical.encode()).hexdigest()
  if not hmac.compare_digest(digest,str(claimed)):return self.reply(409,{"state":"HASH_CONFLICT"})
  with sqlite3.connect(DB,timeout=5) as db:
   db.execute("BEGIN IMMEDIATE")
   row=db.execute("SELECT args_hash,external_id FROM tickets WHERE namespace=? AND operation_key=?",(NAMESPACE,key)).fetchone()
   if row and row[0]!=digest:return self.reply(409,{"state":"KEY_CONFLICT"})
   external=row[1] if row else "ticket-"+uuid.uuid4().hex
   if row:db.execute("UPDATE tickets SET requests=requests+1 WHERE namespace=? AND operation_key=?",(NAMESPACE,key))
   else:db.execute("INSERT INTO tickets VALUES(?,?,?,?,?,1,1)",(NAMESPACE,key,digest,canonical,external))
   control=db.execute("SELECT response_lost,delay_ms FROM controls WHERE namespace=?",(NAMESPACE,)).fetchone() or (0,0)
   if control[0]>0:db.execute("UPDATE controls SET response_lost=response_lost-1 WHERE namespace=?",(NAMESPACE,))
   db.commit()
  if control[1]:time.sleep(control[1]/1000)
  if control[0]>0:self.close_connection=True;self.connection.close();return
  return self.reply(201,{"state":"FOUND","final":True,"operationKey":key,"argsHash":digest,"externalId":external})
ThreadingHTTPServer(("127.0.0.1",19520),Handler).serve_forever()
