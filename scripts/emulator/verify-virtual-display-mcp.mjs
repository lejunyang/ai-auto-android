/**
 * 脚本用途：在固定一次性模拟器用真实aactl MCP stdio验证两种策略及显式目标观察/六类动作。
 * 安全约束：helper启停与本地fixture部署只走操作员CLI；不调用Bridge、任意shell或新增模型工具。
 */
import fs from 'node:fs';
import path from 'node:path';
import { DisplaySession,options,ensure,point,node,attr,pause,sha256,fixturePackage } from './focus-session.mjs';
import { pngStats } from './virtual-display-png.mjs';
import { startMCP,toolPayload } from './mcp-stdio.mjs';
const opt=options(process.argv.slice(2)),s=new DisplaySession(opt,'mcp');let peer;
async function call(name,args,expectedCode) {
 s.guard();const input={device:s.serial,...args};
 const result=await peer.request('tools/call',{name,arguments:input});
 s.lastEnvelope={transport:'MCP stdio',tool:name,arguments:input,observedAt:new Date().toISOString(),result};
 s.event('mcp-call',{name,arguments:input,isError:result.isError===true});
 return {data:toolPayload(result,expectedCode),result};
}
async function hierarchy(id) {
 const {data}=await call('android_observe',{kind:'hierarchy',displayId:id});
 s.lastXML=data.xml;s.lastObservation={displayId:id,observedAt:new Date().toISOString(),xml:data.xml};
 const ids=[...data.xml.matchAll(/<display id="(\d+)"/g)].map(m=>Number(m[1]));
 ensure(data.device===s.serial&&data.displayId===id&&data.format==='uiautomator-windows-xml'&&ids.length===1&&ids[0]===id,'MCP hierarchy selected wrong display');
 ensure(sha256(Buffer.from(data.xml))===data.sha256&&Buffer.byteLength(data.xml)===data.sizeBytes,'MCP hierarchy digest/size mismatch');return data.xml;
}
async function action(id,kind,params,expected) {
 s.event('action',{package:fixturePackage,action:kind,displayId:id,expected,verification:'new MCP target-display hierarchy'});
 await call('android_action_execute',{displayId:id,action:kind,...params});return hierarchy(id);
}
async function tap(id,resource,fraction=0.5,kind='ui.tap') {
 const xy=point(await hierarchy(id),`${fixturePackage}:id/${resource}`,fraction);
 return action(id,kind,{...xy,...(kind==='ui.longClick'?{durationMs:750}:{})},`target ${resource}`);
}
async function frame(id,width,height) {
 const {data,result}=await call('android_observe',{kind:'screenshot',displayId:id}),images=result.content.filter(c=>c.type==='image');
 ensure(images.length===1&&images[0].mimeType==='image/png','Missing MCP PNG image');
 const bytes=Buffer.from(images[0].data,'base64');
 // 失败时保留实际图片，CLI补采截图不能代替MCP返回的像素；成功后不保留额外图片。
 const file=path.join(s.root,'mcp-last.png');fs.writeFileSync(file,bytes);
 ensure(data.displayId===id&&data.format==='png'&&data.sizeBytes===bytes.length&&data.sha256===sha256(bytes),'MCP screenshot metadata mismatch');
 const stats=pngStats(bytes);ensure(stats.width===width&&stats.height===height&&stats.nonBlackRatio>0.01,'MCP frame is invalid or black');fs.unlinkSync(file);s.event('mcp-frame',{displayId:id,...stats});
}
try {
 await s.prepare({startHelper:false});s.hierarchy(0);peer=startMCP(opt);
 const hello=await peer.request('initialize',{protocolVersion:'2025-11-25',capabilities:{},clientInfo:{name:'aactl-vdisplay-acceptance',version:'1'}});
 ensure(hello.protocolVersion==='2025-11-25'&&hello.serverInfo.name==='aactl','Unexpected MCP handshake');peer.notify('notifications/initialized');
 const listed=await peer.request('tools/list');const names=listed.tools.map(t=>t.name);
 ensure(names.length===20&&new Set(names).size===20&&!names.some(n=>/display_(start|stop)/.test(n)),'Unexpected MCP tool surface');
 const createTool=listed.tools.find(t=>t.name==='android_display_create');ensure(createTool.inputSchema.additionalProperties===false&&JSON.stringify(createTool.inputSchema.properties.focus.enum)==='["isolated","steal"]','Unexpected focus schema');
 s.report.mcp={protocolVersion:hello.protocolVersion,tools:names};
 ensure((await hierarchy(0)).includes('package="com.google.android.apps.nexuslauncher"'),'Explicit display 0 is not primary');await frame(0,320,640);
 await call('android_display_list',{},'DISPLAYD_NOT_RUNNING');const helper=s.startHelper();ensure(helper.protocolVersion===2,'Expected helper v2');
 ensure((await call('android_display_list',{})).data.displays.length===0,'Unexpected initial displays');
 for(const focus of ['isolated','steal']) {
  const created=(await call('android_display_create',{width:720,height:1280,dpi:240,focus})).data,id=created.displayId;
  ensure(id>0&&created.focus===focus&&created.focusIsolated===(focus==='isolated'),'Wrong created focus policy');
  const screens=(await call('android_display_list',{})).data.displays,status=(await call('android_display_status',{})).data;
  ensure(screens.length===1&&screens[0].displayId===id&&screens[0].focus===focus&&status.displays.length===1&&status.displays[0].focus===focus&&status.displays[0].focusIsolated===created.focusIsolated,'List/status lost policy');
  ensure((await action(id,'app.launch',{package:fixturePackage,activity:'.MainActivity'},'fixture PAGE:MAIN')).includes('text="PAGE:MAIN"'),'MCP launch failed');
  let xml=await tap(id,'reset_target');ensure(xml.includes('text="CLICK:0"')&&attr(node(xml,`${fixturePackage}:id/text_input`),'text')==='','Fixture reset failed');
  ensure((await tap(id,'click_target')).includes('text="CLICK:1"'),'MCP tap did not arrive');
  ensure((await tap(id,'long_press_target',0.5,'ui.longClick')).includes('text="LONG_PRESS:1"'),'MCP long click did not arrive');
  const bounds=node(await hierarchy(id),`${fixturePackage}:id/vertical_scroll_target`).match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);ensure(bounds,'Missing scroll bounds');
  const [l,t,r,b]=bounds.slice(1).map(Number),x=Math.floor((l+r)/2);
  xml=await action(id,'ui.swipe',{startX:x,startY:Math.floor(b-(b-t)/5),endX:x,endY:Math.floor(t+(b-t)/5),durationMs:400},'positive VERTICAL_OFFSET');
  for(let i=0;i<5&&!/text="VERTICAL_OFFSET:[1-9]\d*"/.test(xml);i++){await pause(250);xml=await hierarchy(id);}
  ensure(/text="VERTICAL_OFFSET:[1-9]\d*"/.test(xml),'MCP swipe did not reach virtual list');
  xml=await tap(id,'text_input',0.85);ensure(attr(node(xml,`${fixturePackage}:id/text_input`),'focused')==='true','Virtual input not focused');
  ensure((await action(id,'ui.setText',{text:'McpAscii'},'INPUT:McpAscii')).includes('text="INPUT:McpAscii"'),'MCP text did not arrive');
  ensure((await action(id,'ui.pressKey',{key:'DELETE'},'INPUT:McpAsci')).includes('text="INPUT:McpAsci"'),'MCP key did not arrive');await frame(id,720,1280);
  ensure((await hierarchy(0)).includes('package="com.google.android.apps.nexuslauncher"'),'MCP primary observation borrowed virtual content');
  const closed=(await call('android_display_close',{displayId:id})).data;ensure(closed.closed===true&&closed.displayId===id,'MCP close failed');
  ensure((await call('android_display_list',{})).data.displays.length===0&&(await call('android_display_status',{})).data.displays.length===0,'Closed display remains');
  await call('android_observe',{kind:'hierarchy',displayId:id},'DISPLAY_NOT_FOUND');s.event('policy-pass',{focus,displayId:id,actions:['app.launch','ui.tap','ui.longClick','ui.swipe','ui.setText','ui.pressKey']});
 }
 s.report.ok=true;
}catch(error){s.report.ok=false;s.report.error=error.message;}
finally {
 if(peer){try{const exit=await peer.close();ensure(exit.code===0,'MCP process failed');s.report.mcpExit=exit;}catch(error){s.report.ok=false;s.report.mcpCloseError=error.message;}fs.writeFileSync(path.join(s.root,'mcp-stderr.txt'),peer.stderr);}
 s.finish(s.report.ok?null:Error(s.report.error??s.report.mcpCloseError));
}
