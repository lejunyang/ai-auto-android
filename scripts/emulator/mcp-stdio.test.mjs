// 脚本用途：用内存管道验证MCP分帧、响应关联与失败关闭，不连接设备或模拟产品成功。
// 测试用途：防止字节分片、乱序响应、坏帧、超时和错误文本让真实stdio验收误判。
import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { StdioPeer,toolPayload } from './mcp-stdio.mjs';
function fixture(options) {const child=new EventEmitter();Object.assign(child,{stdin:new PassThrough(),stdout:new PassThrough(),stderr:new PassThrough(),kill(){}});return {child,peer:new StdioPeer(child,options)};}
test('stdio correlates fragmented and reversed responses across notifications',async()=>{
 const {child,peer}=fixture();const a=peer.request('a'),b=peer.request('b');
 const wire=JSON.stringify({jsonrpc:'2.0',method:'notifications/test'})+'\n'+JSON.stringify({jsonrpc:'2.0',id:2,result:{value:'中文'}})+'\n'+JSON.stringify({jsonrpc:'2.0',id:1,result:{value:1}})+'\n';
 const bytes=Buffer.from(wire);for(const byte of bytes)child.stdout.write(Buffer.from([byte]));
 assert.deepEqual(await a,{value:1});assert.deepEqual(await b,{value:'中文'});child.emit('close',0,null);
});
test('invalid or oversized stdio fails the session and all subsequent requests',async()=>{
 for(const frame of ['not JSON\n','x'.repeat(101)]){const {child,peer}=fixture({maxLine:100});const first=peer.request('read');child.stdout.write(frame);await assert.rejects(first);await assert.rejects(peer.request('action'));child.emit('close',0,null);}
});
test('timeout and process exit fail pending calls without retrying them',async()=>{
 const {child,peer}=fixture({timeout:10});let lines=0;child.stdin.on('data',()=>lines++);await assert.rejects(peer.request('action'),/do not retry/);assert.equal(lines,1);child.emit('close',0,null);
 const second=fixture();const pending=second.peer.request('observe');second.child.emit('close',1,null);await assert.rejects(pending,/MCP closed/);
});
test('pipe failures reject calls so the acceptance finally block can clean the device',async()=>{
 const {child,peer}=fixture();const pending=peer.request('action');child.stdin.emit('error',Error('EPIPE'));await assert.rejects(pending,/EPIPE/);await assert.rejects(peer.request('action'),/EPIPE/);child.emit('close',1,null);
});
test('tool success and dedicated error must use their actual structured fields',()=>{
 assert.deepEqual(toolPayload({structuredContent:{displayId:2}}),{displayId:2});
 const result={isError:true,content:[{type:'text',text:JSON.stringify({code:'DISPLAY_EMPTY',retryable:false})}]};assert.equal(toolPayload(result,'DISPLAY_EMPTY').code,'DISPLAY_EMPTY');
 assert.throws(()=>toolPayload(result,'DISPLAY_NOT_FOUND'));assert.throws(()=>toolPayload(result));assert.throws(()=>toolPayload({content:[{type:'text',text:'looks successful'}]}));
});
