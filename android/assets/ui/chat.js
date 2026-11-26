'use strict';
const $=id=>document.getElementById(id);
const paths={menu:'<path d="M4 8h16M4 16h11"/>',new:'<path d="M14 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M13 11l7-7M13 11l-1 4 4-1"/>',chevron:'<path d="m7 10 5 5 5-5"/>',mic:'<rect x="9" y="3" width="6" height="12" rx="3"/><path d="M6 11a6 6 0 0 0 12 0M12 17v4"/>',wave:'<path d="M4 10v4M8 6v12M12 3v18M16 7v10M20 10v4"/>',up:'<path d="M12 20V4m-7 7 7-7 7 7"/>',down:'<path d="M12 4v16m-7-7 7 7 7-7"/>',stop:'<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"/>',copy:'<rect x="8" y="7" width="12" height="14" rx="2"/><path d="M15 3H4v13"/>',speaker:'<path d="m4 9 4 0 5-4v14l-5-4H4zM17 8a6 6 0 0 1 0 8"/>',spark:'<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z"/>',search:'<circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/>',dots:'<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',check:'<path d="m5 12 4 4L19 6"/>',box:'<path d="m12 3 9 5-9 5-9-5 9-5zM3 8v9l9 5 9-5V8M12 13v9"/>',phone:'<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 18h4"/>',x:'<path d="m6 6 12 12M18 6 6 18"/>',rename:'<path d="m5 15 11-11 4 4L9 19l-5 1z"/>',archive:'<rect x="3" y="3" width="18" height="5" rx="1"/><path d="M5 8v13h14V8M10 12h4"/>',trash:'<path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7"/>'};
const icon=name=>`<svg viewBox="0 0 24 24" aria-hidden="true">${paths[name]||paths.spark}</svg>`;
paths.pin='<path d="m15 3 6 6-4 1-3 3v4l-2 2-7-7 2-2h4l3-3 1-4ZM9 15l-6 6"/>';
paths.file='<path d="M6 3h8l4 4v14H6zM14 3v5h4M9 12h6m-6 4h6"/>';
paths.settings='<path d="M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z"/><circle cx="12" cy="12" r="3"/>';
paths.network='<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a18 18 0 0 1 0 18 18 18 0 0 1 0-18Z"/>';
paths.shield='<path d="m12 3 8 3v6c0 5-8 9-8 9S4 17 4 12V6zM8 12l3 3 5-6"/>';
paths.bell='<path d="M5 17h14l-2-3V9a5 5 0 0 0-10 0v5zm5 3h4"/>';
paths.back='<path d="m14 6-6 6 6 6"/>';
paths.arrow='<path d="m9 6 6 6-6 6"/>';
paths.user='<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>';
paths.plus='<path d="M12 5v14M5 12h14"/>';
document.querySelectorAll('[data-icon]').forEach(el=>el.innerHTML=icon(el.dataset.icon));
function post(action,value='',extra={}){if(window.Phone)Phone.post(JSON.stringify({action,value,...extra}));}
const escapeHtml=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let state={ready:false,busy:false,chatLoading:false,chatKey:'',chatId:'',loggedIn:false,model:'',effort:'',models:[],threads:[],outbox:[],queuePaused:false,modelsLoading:false,modelsError:'',servers:[]},drawerOpen=false,sheetKind='',sheetData=null,searchTimer,toastTimer;
const sheetHistory=[];let sheetOpener=null;
let deliveryMode='queue',submitting=false,outboxSignature='';
let attachments=[],attachmentsLoading=false;
const messages=new Map(),elements=new Map(),codeTexts=new Map(),expanded=new Map();
let renderPending=false,wasBusy=false,chatSignature='',metricsTimer=0;
const metrics={liveTextPaints:0,reasoningPaints:0,toolPaints:0,firstLivePaintAt:0,lastTurnFinishedAt:0};
const effortLabels={'':'авто',none:'без размышлений',minimal:'минимум',low:'низкое',medium:'среднее',high:'высокое',xhigh:'очень высокое',max:'максимум',ultra:'ультра'};
const effortDetails={none:'Ответ без дополнительного размышления',minimal:'Минимальная задержка',low:'Для простых и быстрых задач',medium:'Баланс скорости и глубины',high:'Для сложных задач',xhigh:'Больше времени на сложные задачи',max:'Максимальная глубина',ultra:'Наибольшая глубина для этой модели'};
function selectedModel(){return state.models.find(m=>m.model===state.model||m.id===state.model)||state.models.find(m=>m.isDefault);}
function toast(text){clearTimeout(toastTimer);$('toast').textContent=text;$('toast').classList.add('show');toastTimer=setTimeout(()=>$('toast').classList.remove('show'),1800);}
function atBottom(){return $('scroll').scrollHeight-$('scroll').clientHeight-$('scroll').scrollTop<110;}
function bottom(force=false){if(force||atBottom())$('scroll').scrollTop=$('scroll').scrollHeight;}
function metricsUpdate(){clearTimeout(metricsTimer);metricsTimer=setTimeout(()=>post('metrics','',metrics),350);}
window.applyState=packet=>{
 const beforeBottom=atBottom();
 const accountChanged=['account','loggedIn','loginError','loginPending','loginWorking','target'].some(key=>packet[key]!==undefined&&packet[key]!==state[key]);
 const serversChanged=packet.servers&&JSON.stringify(packet.servers)!==JSON.stringify(state.servers);
 const tunnelChanged=packet.tunnel&&JSON.stringify(packet.tunnel)!==JSON.stringify(state.tunnel);
 const accessChanged=packet.phoneAccess&&JSON.stringify(packet.phoneAccess)!==JSON.stringify(state.phoneAccess);
  const catalogChanged=packet.models||(packet.modelsLoading!==undefined&&packet.modelsLoading!==state.modelsLoading)||(packet.modelsError!==undefined&&packet.modelsError!==state.modelsError);
 if(packet.replace){submitting=false;deliveryMode='queue';messages.clear();elements.forEach(e=>e.remove());elements.clear();expanded.clear();codeTexts.clear();}
 if(packet.busy&&!wasBusy){metrics.liveTextPaints=metrics.reasoningPaints=metrics.toolPaints=0;metrics.firstLivePaintAt=metrics.lastTurnFinishedAt=0;}
 if(!packet.busy&&wasBusy){metrics.lastTurnFinishedAt=Date.now();metricsUpdate();}
 wasBusy=packet.busy;Object.assign(state,packet);
  if(catalogChanged&&(sheetKind==='model'||sheetKind==='effort'))openSheet(sheetKind,sheetData);
 if(accessChanged){renderPhoneAccess();if(sheetKind==='phoneAccess')openSheet('phoneAccess');}
 if(serversChanged&&sheetKind==='servers')openSheet('servers');
 if(sheetKind==='settings'&&(accountChanged||serversChanged||tunnelChanged||accessChanged))openSheet('settings');
 if(sheetKind==='account'&&accountChanged)openSheet('account');
 if(tunnelChanged&&['tunnel','subscription'].includes(sheetKind))openSheet(sheetKind,sheetKind==='subscription'?(state.tunnel.subscriptions||[]).find(s=>s.id===sheetData?.id)||sheetData:null);
 for(const update of packet.updates||[]){
  const previous=messages.get(update.id);messages.set(update.id,{...previous,...update,dirty:true});
  if(previous&&previous.text!==update.text){
   if(update.kind==='assistant'&&packet.busy){metrics.liveTextPaints++;if(!metrics.firstLivePaintAt)metrics.firstLivePaintAt=Date.now();}
   if(update.kind==='reasoning')metrics.reasoningPaints++;
   if(update.kind==='command')metrics.toolPaints++;
   metricsUpdate();
  }
 }
 if(packet.order){
  const ids=new Set(packet.order);for(const [id,el] of elements)if(!ids.has(id)){el.remove();elements.delete(id);messages.delete(id);}
  state.order=packet.order;
 }
 state.follow=packet.replace||beforeBottom;
 if(!renderPending){renderPending=true;requestAnimationFrame(render);}
};
function linkTarget(value){value=String(value).trim();if(value.startsWith('<')&&value.endsWith('>'))value=value.slice(1,-1);if(/^(?:https?:\/\/|file:\/\/|sandbox:\/|\/)/i.test(value)&&!/[\u0000-\u001f]/.test(value))return value;return null;}
function anchor(label,target){const safe=linkTarget(target);return safe?`<a href="#" data-link="${escapeHtml(safe)}">${escapeHtml(label)}</a>`:escapeHtml(label);}
function plainInline(source){const re=/(?:https?:\/\/[^\s<>"`]+|(?:sandbox:|file:\/\/)?\/(?:sdcard|storage|data|mnt)\/[^\s<>"`]+)/g;let out='',at=0,m;while((m=re.exec(source))){let url=m[0].replace(/[.,;!?]+$/,'');while(url.endsWith(')')&&(url.match(/\)/g)||[]).length>(url.match(/\(/g)||[]).length)url=url.slice(0,-1);out+=escapeHtml(source.slice(at,m.index))+anchor(url,url)+escapeHtml(m[0].slice(url.length));at=re.lastIndex;}return out+escapeHtml(source.slice(at));}
function inline(source){
 let out='',at=0,i=0;while(i<source.length){
  const start=i;let end=-1,rendered='';
  if(source[i]==='`'){end=source.indexOf('`',i+1);if(end>i){rendered=`<code>${escapeHtml(source.slice(i+1,end))}</code>`;end++;}}
  else if(source.startsWith('**',i)){end=source.indexOf('**',i+2);if(end>i){rendered=`<strong>${escapeHtml(source.slice(i+2,end))}</strong>`;end+=2;}}
  else if(source[i]==='['){const close=source.indexOf('](',i+1);if(close>=0){let depth=1,p=close+2;for(;p<source.length&&depth;p++){if(source[p]==='(')depth++;if(source[p]===')')depth--;}if(!depth){end=p;rendered=anchor(source.slice(i+1,close),source.slice(close+2,p-1));}}}
  if(end>start){out+=plainInline(source.slice(at,start))+rendered;i=at=end;}else i++;
 }return out+plainInline(source.slice(at));
}
function imageHtml(images){return (images||[]).filter(i=>/^[a-f0-9-]{36}\.(png|jpg)$/.test(i.id)).map(i=>`<button class="chat-image" data-image="${escapeHtml(i.id)}" aria-label="Открыть изображение"><img src="/media/${escapeHtml(i.id)}" alt="Изображение" loading="lazy"></button>`).join('');}
function fileHtml(files){
 return (files||[]).map(file=>`<button class="file-chip" data-open-attachment="${escapeHtml(file.id)}">${icon('file')}<span>${escapeHtml(file.name)}</span></button>`).join('');
}
function markdown(source,id){
 for(const key of codeTexts.keys())if(key.startsWith(id+'/'))codeTexts.delete(key);
 const lines=source.split('\n');let html='',para=[],code=null,lang='',codeIndex=0,list='';
 const flush=()=>{if(para.length){html+=`<p>${para.map(inline).join('<br>')}</p>`;para=[];}if(list){html+=`</${list}>`;list='';}};
 const codeBlock=()=>{const key=id+'/'+codeIndex++;codeTexts.set(key,code.join('\n'));html+=`<div class="code"><div class="code-top"><span>${escapeHtml(lang||'code')}</span><button data-code="${escapeHtml(key)}">${icon('copy')}Копировать</button></div><pre><code>${escapeHtml(code.join('\n'))}</code></pre></div>`;code=null;};
 for(let i=0;i<lines.length;i++){
  const line=lines[i],fence=line.match(/^\s*```(.*)$/);
  if(fence){if(code!==null)codeBlock();else{flush();code=[];lang=fence[1].trim();}continue;}
  if(code!==null){code.push(line);continue;}
  if(!line.trim()){flush();continue;}
  if(i+1<lines.length&&line.includes('|')&&/^\s*\|?\s*:?-{3,}.*\|/.test(lines[i+1])){
   flush();const cells=s=>s.trim().replace(/^\||\|$/g,'').split('|').map(x=>x.trim());html+='<div class="table-wrap"><table><thead><tr>'+cells(line).map(x=>`<th>${inline(x)}</th>`).join('')+'</tr></thead><tbody>';i++;
   while(i+1<lines.length&&lines[i+1].includes('|')&&lines[i+1].trim()){i++;html+='<tr>'+cells(lines[i]).map(x=>`<td>${inline(x)}</td>`).join('')+'</tr>';}
   html+='</tbody></table></div>';continue;
  }
  const heading=line.match(/^(#{1,5})\s+(.+)$/);if(heading){flush();const n=Math.min(heading[1].length+1,4);html+=`<h${n}>${inline(heading[2])}</h${n}>`;continue;}
  const li=line.match(/^\s*(?:([-*])|\d+\.)\s+(.+)$/);if(li){if(para.length){html+=`<p>${para.map(inline).join('<br>')}</p>`;para=[];}const kind=li[1]?'ul':'ol';if(list!==kind){if(list)html+=`</${list}>`;html+=`<${kind}>`;list=kind;}html+=`<li>${inline(li[2])}</li>`;continue;}
  if(line.startsWith('> ')){flush();html+=`<blockquote>${inline(line.slice(2))}</blockquote>`;continue;}
  if(list){html+=`</${list}>`;list='';}para.push(line);
 }
 if(code!==null)codeBlock();flush();return html;
}
function workHtml(m){
 const running=!m.complete,title=m.kind==='reasoning'?(running?'Думаю…':m.durationMs>900?`Думал ${Math.round(m.durationMs/1000)} с`:'Размышление'):(m.title||'Выполнение');
 const body=m.kind==='plan'?(()=>{try{return JSON.parse(m.text).map(s=>(s.status==='completed'?'✓ ':s.status==='inProgress'?'→ ':'○ ')+s.step).join('\n');}catch{return m.text;}})():m.text;
 return `<details class="work ${m.kind==='command'?'command':''} ${m.status==='failed'?'failed':''}" ${expanded.get(m.id)===true||(m.kind==='reasoning'&&running&&expanded.get(m.id)!==false)?'open':''}><summary>${running?'<span class="spinner"></span>':icon(m.status==='failed'?'x':'check')}<span>${escapeHtml(title)}</span>${body?`<span class="chevron">${icon('chevron')}</span>`:''}</summary><div class="work-body">${escapeHtml(body)}</div></details>`;
}
function render(){
 renderPending=false;const ids=state.order||Array.from(messages.keys());
 let next=$('messages').firstElementChild;
 for(const id of ids){const m=messages.get(id);if(!m)continue;let el=elements.get(id);
  if(!el){el=document.createElement('article');el.dataset.id=id;elements.set(id,el);}
  if(el!==next)$('messages').insertBefore(el,next);
  next=el.nextElementSibling;
  if(!m.dirty)continue;m.dirty=false;
  el.className='message '+m.kind+(m.phase==='commentary'?' intermediate':'')+(!m.complete&&m.kind==='assistant'?' streaming':'');
   if(m.kind==='assistant')el.innerHTML=`<div class="message-body">${markdown(m.text,id)}</div><div class="message-actions"><button data-copy-message="${escapeHtml(id)}" aria-label="Копировать">${icon('copy')}</button><button data-speak="${escapeHtml(id)}" aria-label="Озвучить">${icon('speaker')}</button></div>`;
   else if(m.kind==='image')el.innerHTML=imageHtml(m.images)+(m.images?.length?'':`<span>${escapeHtml(m.text)}</span>`);
   else if(m.kind==='user')el.innerHTML=imageHtml(m.images)+fileHtml(m.files)+(m.text?`<span>${escapeHtml(m.text)}</span>`:'');
  else if(['reasoning','command','plan'].includes(m.kind)){el.innerHTML=workHtml(m);const d=el.querySelector('details');d.addEventListener('toggle',()=>expanded.set(id,d.open));}
  else el.textContent=m.text;
 }
 document.querySelector('#welcome h1').textContent=state.target==='pc'?'Что сделать на ПК?':'Чем я могу помочь?';
 document.querySelector('.suggestions').hidden=state.target==='pc';
 $('prompt').placeholder=state.target==='pc'?'Спросить Codex на ПК':'Спросить Codex на телефоне';
 $('welcome').hidden=ids.length>0||state.chatLoading;$('messages').hidden=ids.length===0;
 const active=Array.from(messages.values()).some(m=>!m.complete&&(m.kind==='reasoning'||m.kind==='command'||m.kind==='assistant'&&m.text));
 $('pending').hidden=!state.chatLoading&&(!state.busy||active);
 $('pending').lastElementChild.textContent=state.chatLoading?'Загружаю чат…':'Думаю…';
  const modelName=selectedModel()?.displayName||state.model||'авто';
  $('model-name').textContent='Codex · '+modelName;
  $('current-chat-title').textContent=state.title||'';
  $('composer-model-name').textContent='Модель · '+modelName;
 $('effort-name').textContent=state.effort?'Думать: '+(effortLabels[state.effort]||state.effort):'Размышление';
 $('account-label').textContent=state.loggedIn?(state.account.split(' · ')[0]||'Аккаунт ChatGPT'):'Войти в ChatGPT';
 const tunnelError=state.tunnel?.enabled&&state.tunnel?.error;
 $('connection').textContent=tunnelError?'Ошибка Xray · без обхода':(state.target==='pc'?'На ПК':'На телефоне')+' · '+(!state.ready?state.status:state.status.includes('Ошибка')?'Ошибка':'Подключено');
  $('connection').classList.toggle('error',Boolean(tunnelError)||state.status.includes('Ошибка'));updateComposer();renderOutbox();
 if(drawerOpen)renderChats();if(state.follow)bottom(true);
 $('to-bottom').hidden=atBottom()||ids.length===0;
}
function updateComposer(){
 const typed=$('prompt').value.trim().length>0||attachments.length>0;
 $('send').hidden=!typed;$('voice').hidden=state.busy||typed;$('stop').hidden=!state.busy;
 $('stop').disabled=!state.ready;$('send').disabled=!state.ready||state.chatLoading||submitting||attachmentsLoading;
 $('send').setAttribute('aria-label',state.busy?(deliveryMode==='steer'?'Уточнить текущую задачу':'Добавить в очередь'):'Отправить');
 $('delivery-button').hidden=!state.busy;$('delivery-name').textContent=deliveryMode==='steer'?'Уточнить сейчас':'В очередь';
}
window.setAttachments=packet=>{
 attachments=packet.attachments||packet.images||[];attachmentsLoading=packet.loading;
 const panel=$('attachments');panel.hidden=!attachments.length&&!attachmentsLoading;
 panel.innerHTML=attachments.map(item=>{
  const label=item.url?'Убрать изображение':'Убрать файл';
  const body=item.url?`<img src="${escapeHtml(item.url)}" alt="Фото">`:`<div class="file-chip">${icon('file')}<span>${escapeHtml(item.name)}</span></div>`;
  return `<div class="attachment ${item.url?'':'file-attachment'}">${body}<button data-remove-attachment="${escapeHtml(item.id)}" aria-label="${label}">${icon('x')}</button></div>`;
 }).join('')+(attachmentsLoading?'<span class="attachment-loading">Добавляю вложения…</span>':'');
 updateComposer();
};
window.pasteText=packet=>{const p=$('prompt');p.setRangeText(packet.text,p.selectionStart,p.selectionEnd,'end');p.dispatchEvent(new Event('input'));p.focus();};
window.setNativeDraft=packet=>{if($('prompt').value!==packet.text){$('prompt').value=packet.text;resizeInput();updateComposer();}};
function resizeInput(){const p=$('prompt');p.style.height='auto';p.style.height=Math.min(175,p.scrollHeight)+'px';}
function postDraft(){post('draft',$('prompt').value,{chatKey:state.chatKey});}
function send(){
 if(submitting||!state.ready||state.chatLoading||attachmentsLoading)return;
 if(!state.loggedIn){openSheet('account');return;}
 const value=$('prompt').value.trim();if(!value&&!attachments.length)return;
 submitting=true;updateComposer();post('send',value,{mode:state.busy?deliveryMode:'queue',chatKey:state.chatKey});
}
window.submissionResult=packet=>{
 if(packet.chatKey!==undefined&&packet.chatKey!==state.chatKey)return;
 submitting=false;
 if(packet.accepted&&$('prompt').value.trim()===packet.value.trim()){$('prompt').value='';resizeInput();}
 updateComposer();
 if(packet.accepted){bottom(true);if(state.busy)toast(deliveryMode==='steer'?'Отправляю уточнение…':'Сообщение принято');}
};
function renderOutbox(){const signature=JSON.stringify([state.outbox,state.queuePaused,state.busy]);if(signature===outboxSignature)return;outboxSignature=signature;const entries=state.outbox||[];$('outbox').hidden=!entries.length;$('outbox-title').textContent=(state.queuePaused?'Очередь на паузе':'В очереди')+' · '+entries.length;$('queue-toggle').textContent=state.queuePaused?'Продолжить':'Пауза';$('queue-toggle').disabled=!state.ready;const list=$('queued-messages');list.replaceChildren();for(const e of entries){const row=document.createElement('div');row.className='queued-row';const text=document.createElement('div');text.className='queued-text';const label=document.createElement('small');label.textContent=e.status==='sending'?'Отправляю…':e.status==='failed'?e.error:e.mode==='steer'?'Уточнение текущей задачи':'После текущего ответа';const body=document.createElement('span');body.textContent=e.text||('Вложений: '+e.attachmentCount);text.append(label,body);row.append(text);if(state.busy&&e.status!=='sending'){const steer=document.createElement('button');steer.className='queued-steer';steer.textContent='Сейчас';steer.setAttribute('aria-label','Отправить уточнение сейчас');steer.onclick=()=>post('steerQueued',e.id);row.append(steer);}const remove=document.createElement('button');remove.className='icon-button';remove.innerHTML=icon('x');remove.setAttribute('aria-label','Удалить из очереди');remove.disabled=e.status==='sending';remove.onclick=()=>post('removeQueued',e.id);row.append(remove);list.append(row);}}
function openDrawer(){drawerOpen=true;$('drawer-backdrop').hidden=false;$('drawer').classList.add('open');$('drawer').setAttribute('aria-hidden','false');post('searchChats',$('chat-search').value);renderChats();}
function closeDrawer(){drawerOpen=false;$('drawer-backdrop').hidden=true;$('drawer').classList.remove('open');$('drawer').setAttribute('aria-hidden','true');$('chat-search').blur();}
function renderChats(){
 const signature=JSON.stringify([state.threads,state.chatId,state.hasMoreChats,$('chat-search').value]);
 if(signature===chatSignature)return;
 chatSignature=signature;
 const list=$('chat-list');list.replaceChildren();
 if(!state.threads.length){
  const empty=document.createElement('div');empty.className='empty-list';empty.textContent=$('chat-search').value?'Ничего не найдено':'Здесь появятся твои чаты';list.append(empty);
 }
 for(const [pinned,label] of [[true,'Закреплённые'],[false,'Чаты']]){
  const chats=state.threads.filter(chat=>Boolean(chat.pinned)===pinned);
  if(!chats.length)continue;
  const heading=document.createElement('h2');heading.className='drawer-label';heading.textContent=label;list.append(heading);
  for(const chat of chats){
   const row=document.createElement('div');row.className='chat-row';
   const selected=chat.id===state.chatId;
   row.classList.toggle('selected',selected);
   const open=document.createElement('button');open.textContent=chat.name||chat.preview||'Новый чат';
   if(selected)open.setAttribute('aria-current','true');
   open.onclick=()=>{post('openChat',chat.id);closeDrawer();};
   row.append(open);
   if(chat.busy||chat.needsApproval){
    const badge=document.createElement('span');badge.className='chat-working';
    badge.classList.add(chat.needsApproval?'waiting':'running');
    const label=chat.needsApproval?'Ждёт подтверждения':'Задача выполняется';badge.setAttribute('role','img');badge.setAttribute('aria-label',label);badge.title=label;row.append(badge);
   }
   if(!chat.draft){
    const more=document.createElement('button');more.className='icon-button';more.setAttribute('aria-label','Действия с чатом');more.innerHTML=icon('dots');
    more.onclick=()=>openSheet('chat',chat);row.append(more);
   }
   list.append(row);
  }
 }
 $('more-chats').hidden=!state.hasMoreChats;
}
function option(label,action,description='',selected=false,danger=false,whichIcon=''){
 const b=document.createElement('button');b.className='option'+(selected?' selected':'')+(danger?' danger':'');
 b.innerHTML=(whichIcon?`<span class="option-icon">${icon(whichIcon)}</span>`:'')+`<span class="label"><strong>${escapeHtml(label)}</strong>${description?`<small>${escapeHtml(description)}</small>`:''}</span>`+(selected?`<span class="option-check">${icon('check')}</span>`:'');
 b.addEventListener('click',action);return b;
}
function navOption(label,action,description='',whichIcon='settings'){
 const b=option(label,action,description,false,false,whichIcon);b.classList.add('navigates');b.insertAdjacentHTML('beforeend',`<span class="option-arrow">${icon('arrow')}</span>`);return b;
}
function tunnelOption(label,action,description='',selected=false,disabled=false,whichIcon=''){
 const b=option(label,action,description,selected,false,whichIcon);b.disabled=disabled;return b;
}
function section(parent,label,...children){
 const block=document.createElement('section');block.className='settings-section';
 if(label){const title=document.createElement('h3');title.className='section-label';title.textContent=label;block.append(title);}
 const group=document.createElement('div');group.className='settings-group';group.append(...children.filter(Boolean));block.append(group);parent.append(block);return group;
}
function note(parent,text,tone=''){
 if(!text)return;const el=document.createElement('p');el.className='sheet-subtitle'+(tone?' note-'+tone:'');el.textContent=text;if(tone==='error')el.setAttribute('role','alert');parent.append(el);return el;
}
function statusCard(parent,which,title,subtitle='',tone=''){
 const card=document.createElement('div');card.className='status-card '+tone;card.innerHTML=`<span class="status-icon">${icon(which)}</span><div><strong>${escapeHtml(title)}</strong>${subtitle?`<small>${escapeHtml(subtitle)}</small>`:''}</div>`;parent.append(card);return card;
}
function help(parent,title,text){const d=document.createElement('details');d.className='sheet-help';const summary=document.createElement('summary');summary.textContent=title;const p=document.createElement('p');p.textContent=text;d.append(summary,p);parent.append(d);}
function switchRow(label,description,checked,disabled,action,key=''){
 const row=document.createElement('label');row.className='access-toggle';const text=document.createElement('span');const title=document.createElement('strong');title.textContent=label;const small=document.createElement('small');small.textContent=description;text.append(title,small);
 const toggle=document.createElement('input');toggle.type='checkbox';toggle.setAttribute('role','switch');toggle.setAttribute('aria-label',label);if(key)toggle.dataset.access=key;toggle.checked=Boolean(checked);toggle.disabled=Boolean(disabled);toggle.onchange=()=>{toggle.disabled=true;action(toggle.checked);};row.append(text,toggle);return row;
}
function sheetBack(){if(!sheetHistory.length){closeSheet();return;}const previous=sheetHistory.pop();sheetKind='';openSheet(previous.kind,previous.data);$('sheet').scrollTop=previous.scroll||0;}
function openSheet(kind,data=null){
 const same=sheetKind===kind,scroll=same?$('sheet').scrollTop:0;
 const active=document.activeElement,focusLabel=same&&$('sheet-content').contains(active)?active.getAttribute('aria-label')||active.querySelector('strong')?.textContent:null;
 if(!sheetKind){if(!sheetOpener)sheetOpener=document.activeElement;if(['account','devices','tunnel','servers','phoneAccess','maintenance'].includes(kind)&&!sheetHistory.length)sheetHistory.push({kind:'settings',data:null,scroll:0});}
 else if(!same)sheetHistory.push({kind:sheetKind,data:sheetData,scroll:$('sheet').scrollTop});
 sheetKind=kind;sheetData=data;const c=$('sheet-content');c.replaceChildren();$('sheet').dataset.kind=kind;
 const titles={settings:'Настройки',maintenance:'Обслуживание',model:'Модель',effort:'Размышление',delivery:'Во время задачи',account:'Аккаунт ChatGPT',chat:'Действия с чатом',rename:'Название чата',delete:'Удалить чат?',logout:'Выйти из аккаунта?',devices:'Где работает Codex',phoneAccess:'Доступ к телефону',servers:'Рабочие серверы',server:'Рабочий сервер',tunnel:'Xray',subscription:'Подписка Xray',attach:'Вложения'};
 const header=document.createElement('div');header.className='sheet-topbar';
 const back=document.createElement('button');back.className='icon-button sheet-back';back.setAttribute('aria-label','Назад');back.innerHTML=icon('back');back.hidden=!sheetHistory.length;back.onclick=sheetBack;
 const heading=document.createElement('h2');heading.className='sheet-title';heading.id='sheet-title';heading.textContent=titles[kind]||'Codex';
 const close=document.createElement('button');close.className='icon-button sheet-close';close.setAttribute('aria-label','Закрыть панель');close.innerHTML=icon('x');close.onclick=closeSheet;header.append(back,heading,close);c.append(header);
 if(kind==='settings'){
  const account=navOption(state.loggedIn?(state.account?.split(' · ')[0]||'Аккаунт ChatGPT'):'Войти в ChatGPT',()=>openSheet('account'),state.loggedIn?'Аккаунт подключён':'Используй свой аккаунт ChatGPT','user');account.classList.add('profile-card');c.append(account);
  const connection=navOption('Где работает Codex',()=>openSheet('devices'),state.target==='pc'?'Мой ПК · SSH':'Этот телефон · Android и Docker','phone');
  const network=navOption('Xray · Подписки и хосты',()=>openSheet('tunnel'),state.tunnel?.enabled?(state.tunnel.error?'Ошибка подключения':state.tunnel.selectedName||'Выбери сервер'):'Системная сеть','network');if(state.tunnel?.enabled&&state.tunnel.error)network.classList.add('has-error');
  section(c,'Подключение',connection,network,navOption('Рабочие серверы',()=>openSheet('servers'),(state.servers||[]).length?'Сохранено: '+state.servers.length:'SSH-серверы по названию','box'));
  const access=state.phoneAccess||{};
  section(c,'Приложение',navOption('Доступ к телефону',()=>openSheet('phoneAccess'),access.status?.active?'Сейчас используется доступ':'Фоновые приложения и анализ трафика','shield'),navOption('Уведомления о задачах',()=>{post('notificationSettings');closeSheet();},'Звук, шторка и экран блокировки','bell'));
  section(c,'Система',navOption('Обслуживание',()=>openSheet('maintenance'),'Диагностика и управление помощником','settings'));
  note(c,'Codex Mobile · Копия'+(state.appVersion?' · '+state.appVersion:''),'version');
 }
 if(kind==='maintenance'){
  section(c,'Диагностика',option(state.target==='pc'?'Проверить подключение ПК':'Проверить телефон и Docker',()=>{post('diagnostics');closeSheet();},state.target==='pc'?'Имя компьютера и рабочая папка':'Состояние Android, root и контейнеров',false,false,'check'));
  section(c,'Помощник',option('Остановить помощника',()=>{post('stopEngine');closeSheet();},'Контейнеры продолжат работать',false,true,'stop'));
  note(c,'Codex Mobile · Копия'+(state.appVersion?' · '+state.appVersion:''),'version');
 }
 if(kind==='attach'){heading.textContent='Вложения';c.append(option('Выбрать из галереи',()=>{post('pickImages');closeSheet();}));c.append(option('Выбрать файлы',()=>{post('pickFiles');closeSheet();},'До 20 МБ на файл',false,false,'file'));c.append(option('Вставить из буфера',()=>{post('pasteClipboard');closeSheet();}));c.append(option('Создать изображение',()=>{closeSheet();$('prompt').value='Создай изображение: ';$('prompt').dispatchEvent(new Event('input'));$('prompt').focus();},'Опиши, что нужно нарисовать'));}
 if(kind==='model'){c.append(option('Автоматически',()=>{post('model','');closeSheet();},'Рекомендованная модель',!state.model));for(const m of state.models.filter(x=>!x.hidden))c.append(option(m.displayName||m.model||m.id,()=>{post('model',m.model||m.id);closeSheet();},'',state.model===(m.model||m.id)));if(state.busy){const note=document.createElement('p');note.className='sheet-subtitle';note.textContent='Выбор применяется к новым сообщениям. Уточнение текущей задачи использует её модель.';c.append(note);}c.append(option(state.modelsLoading?'Загружаю модели…':'Обновить список',()=>{if(!state.modelsLoading)post('refreshModels');},state.modelsError||(!state.models.length&&!state.modelsLoading?'Список пока пуст. Проверь подключение и обнови его.':'')));}
 if(kind==='delivery'){for(const [mode,label,description]of[['queue','В очередь','Отправить после завершения текущего ответа'],['steer','Уточнить сейчас','Добавить указание к уже выполняемой задаче']])c.append(option(label,()=>{deliveryMode=mode;closeSheet();updateComposer();},description,deliveryMode===mode));}
 if(kind==='effort'){c.append(option('Автоматически',()=>{post('effort','');closeSheet();},'Рекомендуемый уровень модели',!state.effort));for(const e of selectedModel()?.supportedReasoningEfforts||[])c.append(option(effortLabels[e.reasoningEffort]||e.reasoningEffort,()=>{post('effort',e.reasoningEffort);closeSheet();},effortDetails[e.reasoningEffort]||e.description,state.effort===e.reasoningEffort));}
 if(kind==='account'){
  statusCard(c,'user',state.loggedIn?(state.account?.split(' · ')[0]||'Аккаунт ChatGPT'):'Вход в ChatGPT',state.loggedIn?(state.account?.split(' · ').slice(1).join(' · ')||'Аккаунт подключён'):'История Codex хранится на твоих устройствах',state.loggedIn?'connected':'');
  note(c,state.loginError?'Ошибка входа: '+state.loginError:'', 'error');
  if(state.target!=='pc'){
   const actions=[];
   if(state.loginWorking){const pending=option('Подготавливаю вход…',()=>{},'Дождись ответа движка',false,false,'user');pending.disabled=true;actions.push(pending);}
   else if(state.loginPending){actions.push(option('Продолжить вход',()=>{post('continueLogin');closeSheet();},'Текущий код сохранится',false,false,'user'),option('Получить новый код',()=>{post('restartLoginDevice');closeSheet();},'Отменит предыдущую попытку'),option('Начать новый вход через браузер',()=>{post('restartLoginBrowser');closeSheet();}));}
   else if(!state.loggedIn){actions.push(option('Войти по коду',()=>{post('loginDevice');closeSheet();},'С телефона или другого устройства',false,false,'user'),option('Войти через браузер',()=>{post('loginBrowser');closeSheet();},'',false,false,'network'));}
   else actions.push(option('Проверить вход',()=>{post('checkAccount');closeSheet();},'Обновить состояние аккаунта',false,false,'check'),option('Войти заново по коду',()=>{post('loginDevice');closeSheet();},'',false,false,'user'));
   section(c,'Авторизация',...actions);
  }
  if(state.loggedIn&&state.target!=='pc')section(c,'',option('Выйти из аккаунта',()=>openSheet('logout'),'',false,true,'user'));
 }
 if(kind==='tunnel'){
  const t=state.tunnel||{};
  const checked=t.status?.match(/TLS OK · (\d{3})/);
  statusCard(c,'network',t.enabled?(t.selectedName||'Выбери сервер'):'Системная сеть',t.working?'Проверяю соединение…':checked?'Ответ ChatGPT · HTTP '+checked[1]:t.enabled?'Xray · Только выбранный хост':'Добавь подписку для подключения через Xray',t.error?'error':t.enabled?'connected':'');
  note(c,t.error,'error');
  section(c,'Маршрут',switchRow('Строгий Xray','При сбое — ошибка без прямого обхода',t.enabled,t.working,enabled=>post('enableTunnel','',{enabled})),tunnelOption('Проверить ChatGPT через Xray',()=>post('testTunnel'),t.working?'Проверяю…':'Проверка TLS и ответа ChatGPT',false,Boolean(t.working)||!t.enabled,'check'));
  const add=tunnelOption('Добавить подписку',()=>post('editSubscription'),'Ссылка HTTPS или список серверов',false,Boolean(t.working),'plus');add.classList.add('add-action');
  const group=section(c,'Подписки',add);
  for(const subscription of t.subscriptions||[])group.append(navOption(subscription.name,()=>openSheet('subscription',subscription),subscription.hosts.length+' хостов'+(subscription.warning?' · Есть пропущенные серверы':''),'box'));
  if(!(t.subscriptions||[]).length)note(c,'Подписок пока нет. Добавь свою ссылку, затем выбери хост.');
  help(c,'Как работает маршрут','Весь трафик локального движка Codex, включая домены ChatGPT, идёт через выбранный хост. Без автоматического переключения на другой сервер или прямую сеть. Внешний браузер и движок на ПК используют свою сеть.');
 }
 if(kind==='subscription'&&data){
  statusCard(c,'network',data.name,data.hosts.length+' хостов'+(data.updated?' · Обновлено '+new Date(data.updated).toLocaleDateString():''));
  note(c,data.warning);
  const actions=section(c,'Подписка',tunnelOption('Обновить подписку',()=>post('refreshSubscription',data.id),data.refreshable?'Загрузить список заново':'Используй редактирование для вставленных ссылок',false,!data.refreshable||Boolean(state.tunnel?.working),'down'),tunnelOption('Изменить подписку',()=>post('editSubscription',data.id),'Название и источник',false,Boolean(state.tunnel?.working),'rename'));
  actions.classList.add('compact-actions');note(c,state.tunnel?.error,'error');
  const hosts=section(c,'Выбери хост');
  for(const host of data.hosts||[])hosts.append(tunnelOption(host.name,()=>post('selectTunnelHost',host.id),host.protocol.toUpperCase()+' · '+host.host+':'+host.port,state.tunnel?.selected===host.id,Boolean(state.tunnel?.working),'network'));
  const remove=tunnelOption('Удалить подписку',()=>post('removeSubscription',data.id),'Активный маршрут не сменится автоматически',false,Boolean(state.tunnel?.working),'trash');remove.classList.add('danger');section(c,'',remove);
 }
 if(kind==='servers'){
  const add=option('Добавить сервер',()=>{post('editServer','');closeSheet();},'Адрес и данные для подключения',false,false,'plus');add.classList.add('add-action');
  const list=section(c,'Подключения',add);
  for(const server of state.servers||[])list.append(navOption(server.name,()=>openSheet('server',server),server.user+'@'+server.host+':'+server.port,'box'));
  if(!(state.servers||[]).length)statusCard(c,'box','Серверов пока нет','Добавь сервер и назови его в чате');
  note(c,state.serverError,'error');help(c,'Ключи и пароли','Данные хранятся зашифрованными и не передаются в чат. С SSH-ключом пароль означает пароль root для su. Пароль самого ключа вводится отдельно.');
 }
 if(kind==='server'&&data){
  statusCard(c,'box',data.name,data.user+'@'+data.host+':'+data.port);
  section(c,'Работа с сервером',option('Запрос в чате',()=>{closeSheet();closeDrawer();$('prompt').value='Работай на сервере «'+data.name+'». '+$('prompt').value;$('prompt').dispatchEvent(new Event('input'));$('prompt').focus();},'Добавить имя сервера к сообщению',false,false,'new'),option('Проверить подключение',()=>{post('testServer',data.id);closeSheet();},'SSH, пользователь и тип системы',false,false,'check'),option('Изменить',()=>{post('editServer',data.id);closeSheet();},'Данные подключения',false,false,'rename'));
  if(data.hostKeyFingerprint)help(c,'Ключ сервера',data.hostKeyFingerprint);
  section(c,'',option('Удалить профиль',()=>{post('deleteServer',data.id);closeSheet();},'Файлы на сервере останутся на месте',false,true,'trash'));
 }
 if(kind==='phoneAccess'){
  const access=state.phoneAccess||{},active=access.status||{};
  if(active.active)statusCard(c,'shield','Доступ используется',active.package||'Активный сеанс','warning');
  section(c,'Разрешения',switchRow('Приложения в фоне','Помощник работает на отдельном виртуальном экране',access.background,access.changing,enabled=>post('phoneAccess','background',{enabled}),'background'),switchRow('HTTP и HTTPS по необходимости','Для выбранного приложения, с видимым уведомлением',access.traffic,access.changing,enabled=>post('phoneAccess','traffic',{enabled}),'traffic'));
  note(c,'Временный HTTPS-сертификат удаляется после сеанса или сбоя. Само разрешение не запускает запись.');
  if(active.active)section(c,'Активный сеанс',option('Остановить доступ сейчас',()=>post('stopPhoneAccess'),active.package||'',false,true,'stop'));
  note(c,access.error,'error');help(c,'Подробнее о доступе','HTTPS ограничен выбранным приложением и коротким сеансом. Ключ сертификата удаляется, запись остаётся на телефоне. Приложения с закреплением сертификата могут отклонить подключение. Не все приложения поддерживают виртуальный экран.');
 }
 if(kind==='devices'){
  section(c,'Место выполнения',option('Этот телефон',()=>{if(state.busyCount){toast('Сначала дождись завершения задач');return;}post('connectPhone');closeSheet();},'Android, root и Docker',state.target!=='pc',false,'phone'),option('Мой ПК',()=>{if(state.busyCount){toast('Сначала дождись завершения задач');return;}if(!state.pcConfigured){post('pcSettings');return;}post('connectPc');closeSheet();},'Файлы и команды компьютера через SSH',state.target==='pc',false,'box'));
  section(c,'Компьютер',option('Настроить подключение ПК',()=>{post('pcSettings');closeSheet();},'Адрес и публичный SSH-ключ',false,false,'settings'));
  note(c,'Чаты и файлы телефона и ПК хранятся на своих устройствах. Для работы на ПК он должен быть включён и доступен по сети.');
 }
 if(kind==='chat'){c.append(option(data.pinned?'Открепить чат':'Закрепить чат',()=>{post('pinChat',data.id);closeSheet();},'',false,false,'pin'));c.append(option('Переименовать',()=>openSheet('rename',data),'',false,false,'rename'));c.append(option('Архивировать',()=>{post('archiveChat',data.id);closeSheet();},'',false,false,'archive'));c.append(option('Удалить чат',()=>openSheet('delete',data),'',false,true,'trash'));}
 if(kind==='rename'){const input=document.createElement('input');input.className='sheet-input';input.value=data.name||data.preview||'';c.append(input);const buttons=document.createElement('div');buttons.className='sheet-buttons';const cancel=document.createElement('button');cancel.textContent='Отмена';cancel.onclick=closeSheet;const save=document.createElement('button');save.className='confirm';save.textContent='Сохранить';save.onclick=()=>{post('renameChat',input.value,{id:data.id});closeSheet();};buttons.append(cancel,save);c.append(buttons);setTimeout(()=>input.focus(),180);}
 if(kind==='delete'||kind==='logout'){const p=document.createElement('p');p.className='sheet-subtitle';p.textContent=kind==='delete'?'Локальная история этого чата будет удалена.':'Для новых запросов потребуется снова войти.';c.append(p);const buttons=document.createElement('div');buttons.className='sheet-buttons';for(const [label,action,cls]of[['Отмена',closeSheet,''],[kind==='delete'?'Удалить':'Выйти',()=>{post(kind==='delete'?'deleteChat':'logout',data?.id||'');closeSheet();},'danger']]){const b=document.createElement('button');b.textContent=label;b.className=cls;b.onclick=action;buttons.append(b);}c.append(buttons);}
 $('sheet').scrollTop=scroll;$('sheet').setAttribute('aria-labelledby','sheet-title');
 $('sheet-backdrop').hidden=false;$('sheet').classList.add('open');$('sheet').setAttribute('aria-hidden','false');
 if(!same)(back.hidden?close:back).focus({preventScroll:true});else if(focusLabel){const replacement=Array.from(c.querySelectorAll('button,input')).find(el=>(el.getAttribute('aria-label')||el.querySelector('strong')?.textContent)===focusLabel);replacement?.focus({preventScroll:true});}
}
function renderPhoneAccess(){const active=state.phoneAccess?.status||{};const chip=$('phone-access-status');chip.hidden=!active.active;chip.textContent=(active.mode==='https'?'● Временный HTTPS-сертификат · ':'● Анализ трафика · ')+(active.package||'');chip.onclick=()=>openSheet('phoneAccess');}
function closeSheet(){sheetKind='';sheetData=null;sheetHistory.length=0;$('sheet-backdrop').hidden=true;$('sheet').classList.remove('open');$('sheet').setAttribute('aria-hidden','true');document.activeElement?.blur();if(sheetOpener?.isConnected)sheetOpener.focus({preventScroll:true});sheetOpener=null;}
window.handleBack=()=>{if(sheetKind){sheetBack();return true;}if(drawerOpen){closeDrawer();return true;}return false;};
$('menu').onclick=openDrawer;$('close-drawer').onclick=closeDrawer;$('drawer-backdrop').onclick=closeDrawer;$('sheet-backdrop').onclick=closeSheet;
function newChat(){post('newChat');closeDrawer();}
$('new-chat').onclick=newChat;$('drawer-new').onclick=newChat;$('model-button').onclick=$('composer-model').onclick=()=>{openSheet('model');if(!state.models.length&&!state.modelsLoading)post('refreshModels');};$('effort-button').onclick=()=>openSheet('effort');$('delivery-button').onclick=()=>openSheet('delivery');$('account-button').onclick=()=>openSheet('settings');$('connection').onclick=()=>openSheet(state.tunnel?.enabled&&state.tunnel?.error?'tunnel':'devices');$('stop').onclick=()=>post('stopTurn');$('queue-toggle').onclick=()=>post(state.queuePaused?'resumeQueue':'pauseQueue');
$('send').onclick=send;$('voice').onclick=()=>post('voice');$('mic').onclick=()=>post('mic');$('more-chats').onclick=()=>post('moreChats');$('to-bottom').onclick=()=>bottom(true);
$('attach').onclick=()=>openSheet('attach');$('attachments').addEventListener('click',e=>{const b=e.target.closest('[data-remove-attachment]');if(b)post('removeAttachment',b.dataset.removeAttachment,{chatKey:state.chatKey});});
$('prompt').addEventListener('input',()=>{resizeInput();updateComposer();postDraft();});$('prompt').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing&&!matchMedia('(pointer:coarse)').matches){e.preventDefault();send();}});
$('scroll').addEventListener('scroll',()=>{$('to-bottom').hidden=atBottom()||messages.size===0;},{passive:true});
$('chat-search').addEventListener('input',()=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>post('searchChats',$('chat-search').value),250);});
document.querySelectorAll('[data-prompt]').forEach(b=>b.onclick=()=>{$('prompt').value=b.dataset.prompt;resizeInput();updateComposer();postDraft();$('prompt').focus();});
$('messages').addEventListener('click',e=>{
 const link=e.target.closest('a[data-link]');if(link){e.preventDefault();if(linkTarget(link.dataset.link))post('openLink',link.dataset.link);return;}
 const file=e.target.closest('[data-open-attachment]');if(file){post('openAttachment',file.dataset.openAttachment);return;}
 const image=e.target.closest('[data-image]');if(image){post('openImage',image.dataset.image);return;}
 const code=e.target.closest('[data-code]');if(code){post('copyText',codeTexts.get(code.dataset.code)||'');toast('Скопировано');return;}
 const copy=e.target.closest('[data-copy-message]');if(copy){post('copyMessage',copy.dataset.copyMessage);toast('Скопировано');return;}
 const speak=e.target.closest('[data-speak]');if(speak)post('speakMessage',speak.dataset.speak);
});
resizeInput();updateComposer();

document.addEventListener('keydown',event=>{if(!sheetKind)return;if(event.key==='Escape'){event.preventDefault();sheetBack();return;}if(event.key!=='Tab')return;const controls=Array.from($('sheet').querySelectorAll('button:not(:disabled),input:not(:disabled),summary')).filter(el=>el.getClientRects().length);if(!controls.length)return;const first=controls[0],last=controls.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}});
