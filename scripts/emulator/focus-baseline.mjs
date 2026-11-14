/** 脚本用途：用有界观察建立设置搜索基线；重试必须重新定位，不改变焦点或IME验收判据。 */
import { ensure, attr, point, pause } from './focus-session.mjs';
export const primaryPackage='com.google.android.settings.intelligence';
export const primaryInputID=`${primaryPackage}:id/open_search_view_edit_text`;
const tags=xml=>[...xml.matchAll(/<node\b[^>]*>/g)].map(m=>m[0]);
const unique=(xml,predicate)=>{const found=tags(xml).filter(predicate);return found.length===1?found[0]:null;};
export const searchEntry=xml=>unique(xml,n=>n.includes('package="com.android.settings"')&&n.includes('text="Search settings"'));
export const searchInput=xml=>unique(xml,n=>n.includes(`resource-id="${primaryInputID}"`));

/** 两次连续读到同一目标节点才认为稳定；消失或变化都会清除前次匹配。 */
export async function waitStable(read,select,{attempts=6,wait=()=>pause(250),observe=()=>{}}={}) {
 let previous=null,last=null;
 for(let i=0;i<attempts;i++) {
  const xml=await read(),match=select(xml);last={xml,match};observe(i+1,match);
  if(match&&match===previous)return {...last,ready:true};
  previous=match;if(i+1<attempts)await wait();
 }
 return {...last,ready:false};
}

/** 保留窗口原始头与节点数量，明确区分搜索未出现和重复窗口，不借其它窗口的数据。 */
export function inputWindowDiagnostics(xml) {
 const windows=[...xml.matchAll(/<window\b[^>]*>[\s\S]*?<\/window>/g)].map(m=>m[0]);
 return {matchingWindows:windows.filter(w=>w.includes(`resource-id="${primaryInputID}"`)).length,
  inputNodes:tags(xml).filter(n=>n.includes(`resource-id="${primaryInputID}"`)).length,
  packages:[...new Set([...xml.matchAll(/package="([^"]*)"/g)].map(m=>m[1]))],
  windows:windows.map(w=>w.match(/^<window\b[^>]*>/)[0])};
}

export async function openPrimarySearch(s,{attempts=6,wait=()=>pause(250)}={}) {
 const stable=select=>waitStable(()=>s.hierarchy(0),select,{attempts,wait,observe:(attempt,match)=>s.event('baseline-poll',{attempt,selector:select===searchEntry?'settings-entry':'search-input',found:!!match})});
 for(let tapAttempt=1;tapAttempt<=2;tapAttempt++) {
  // 首次及重试都重新读首页节点，不能复用前次点按坐标。
  const home=await stable(searchEntry);ensure(home.ready,'Settings search entry did not stabilize');
  const id=attr(home.match,'resource-id'),xy=point(home.xml,id);
  s.action(0,'tap',['--x',String(xy.x),'--y',String(xy.y)],'open stable Settings search entry','com.android.settings');
  const opened=await stable(searchInput);
  if(opened.ready)return opened;
  s.preserveEvidence(`search-open-${tapAttempt}`);
  s.event('baseline-retry',{tapAttempt,diagnostic:inputWindowDiagnostics(opened.xml),next:tapAttempt===1?'reobserve homepage and relocate once':'fail baseline'});
  if(tapAttempt===2)throw Error('Search input did not appear after two observed taps');
  // 最后一次轮询之后搜索页可能刚出现，先重新观察再决定是否需要第二次动作。
  const current=s.hierarchy(0);
  if(searchInput(current)){const ready=await stable(searchInput);if(ready.ready)return ready;}
 }
 throw Error('Search baseline unavailable');
}

/** 只在helper启动前重建因系统包切换丢失的基线，且最新层级必须明确回到Settings首页。 */
export async function recoverPrimaryBaseline(s,build) {
 ensure(!s.helperStarted,'Cannot rebuild primary baseline after product operations begin');
 for(let attempt=1;attempt<=2;attempt++) {
  try{return await build();}catch(error){
   s.preserveEvidence(`baseline-build-${attempt}`);
   if(attempt===2){s.failureAlreadyPreserved=true;throw error;}
   const current=s.hierarchy(0);
   if(!searchEntry(current)){s.failureAlreadyPreserved=true;throw error;}
   s.event('baseline-rebuild',{attempt,reason:error.message,observed:'Settings homepage; search absent',next:'reopen from current hierarchy once before helper start'});
  }
 }
 throw Error('Primary baseline recovery exhausted');
}
