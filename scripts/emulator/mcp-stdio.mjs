/** 脚本用途：用有界NDJSON连接真实MCP子进程；超时、断连或坏帧后禁止继续发动作。 */
import { spawn } from 'node:child_process';
import { ensure } from './focus-session.mjs';

export class StdioPeer {
 constructor(child,{timeout=60000,maxLine=32*1024*1024}={}) {
  this.child=child;this.timeout=timeout;this.maxLine=maxLine;this.pending=new Map();this.next=0;this.buffer='';this.stderr='';
  this.closed=new Promise(resolve=>child.once('close',(code,signal)=>{this.fail(Error(`MCP closed: ${code}/${signal}`));resolve({code,signal});}));
  child.once('error',error=>this.fail(error));child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');
  for(const stream of [child.stdin,child.stdout,child.stderr])stream.on('error',error=>this.fail(error));
  child.stderr.on('data',chunk=>{this.stderr=(this.stderr+chunk).slice(-16384);});
  child.stdout.on('data',chunk=>{try{this.receive(chunk);}catch(error){this.fail(error);}});
 }
 fail(error) {
  this.fatal??=error;
  for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(this.fatal);}this.pending.clear();
 }
 receive(chunk) {
  this.buffer+=chunk;ensure(Buffer.byteLength(this.buffer)<=this.maxLine,'MCP line budget exceeded');
  let end;
  while((end=this.buffer.indexOf('\n'))>=0) {
   const line=this.buffer.slice(0,end);this.buffer=this.buffer.slice(end+1);if(!line.trim())continue;
   const message=JSON.parse(line);ensure(message.jsonrpc==='2.0','Invalid MCP JSON-RPC version');
   if(message.id===undefined){ensure(typeof message.method==='string','Invalid MCP notification');continue;}
   const p=this.pending.get(message.id);ensure(p,'Unknown or duplicate MCP response ID');
   this.pending.delete(message.id);clearTimeout(p.timer);
   if(message.error){const error=Error(`JSON-RPC ${message.error.code}: ${message.error.message}`);error.response=message;p.reject(error);}
   else if('result' in message)p.resolve(message.result);
   else {p.reject(Error('Missing MCP result'));throw Error('Missing MCP result');}
  }
 }
 request(method,params={}) {
  if(this.fatal)return Promise.reject(this.fatal);
  const id=++this.next;
  return new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>this.fail(Error(`MCP request timed out: ${method}; do not retry actions`)),this.timeout);
   this.pending.set(id,{resolve,reject,timer});
   this.child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n',error=>{if(error)this.fail(error);});
  });
 }
 notify(method,params={}) {ensure(!this.fatal,'MCP session is unusable');this.child.stdin.write(JSON.stringify({jsonrpc:'2.0',method,params})+'\n');}
 async close() {
  this.child.stdin.end();let timer;
  try{return await Promise.race([this.closed,new Promise((_,reject)=>{timer=setTimeout(()=>{this.child.kill();reject(Error('MCP did not exit after stdin EOF'));},5000);})]);}
  finally{clearTimeout(timer);}
 }
}
export function startMCP(opt) {
 return new StdioPeer(spawn(opt.aactl,['mcp','serve'],{windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,MSYS_NO_PATHCONV:'1',AACTL_ADB_PATH:opt.adb}}));
}
/** 成功必须有结构化结果；错误码必须来自工具原文，不能把文本中相似字样当作成功或预期错误。 */
export function toolPayload(result,expectedCode) {
 if(expectedCode) {
  ensure(result.isError===true,'Expected MCP tool error');
  const texts=result.content?.filter(c=>c.type==='text');ensure(texts?.length===1,'Ambiguous MCP tool error');
  const error=JSON.parse(texts[0].text);ensure(error.code===expectedCode&&typeof error.retryable==='boolean',`Unexpected MCP error: ${texts[0].text}`);return error;
 }
 ensure(!result.isError,`MCP tool failed: ${JSON.stringify(result.content)}`);
 ensure(result.structuredContent&&typeof result.structuredContent==='object'&&!Array.isArray(result.structuredContent),'Missing MCP structuredContent');return result.structuredContent;
}
