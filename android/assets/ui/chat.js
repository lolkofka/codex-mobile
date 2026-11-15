'use strict';
const $=id=>document.getElementById(id);
const paths={menu:'<path d="M4 8h16M4 16h11"/>',new:'<path d="M14 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M13 11l7-7M13 11l-1 4 4-1"/>',chevron:'<path d="m7 10 5 5 5-5"/>',mic:'<rect x="9" y="3" width="6" height="12" rx="3"/><path d="M6 11a6 6 0 0 0 12 0M12 17v4"/>',wave:'<path d="M4 10v4M8 6v12M12 3v18M16 7v10M20 10v4"/>',up:'<path d="M12 20V4m-7 7 7-7 7 7"/>',down:'<path d="M12 4v16m-7-7 7 7 7-7"/>',stop:'<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"/>',copy:'<rect x="8" y="7" width="12" height="14" rx="2"/><path d="M15 3H4v13"/>',speaker:'<path d="m4 9 4 0 5-4v14l-5-4H4zM17 8a6 6 0 0 1 0 8"/>',spark:'<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z"/>',search:'<circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/>',dots:'<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',check:'<path d="m5 12 4 4L19 6"/>',box:'<path d="m12 3 9 5-9 5-9-5 9-5zM3 8v9l9 5 9-5V8M12 13v9"/>',phone:'<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 18h4"/>',x:'<path d="m6 6 12 12M18 6 6 18"/>',rename:'<path d="m5 15 11-11 4 4L9 19l-5 1z"/>',archive:'<rect x="3" y="3" width="18" height="5" rx="1"/><path d="M5 8v13h14V8M10 12h4"/>',trash:'<path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7"/>'};
const icon=name=>`<svg viewBox="0 0 24 24" aria-hidden="true">${paths[name]||paths.spark}</svg>`;
paths.plus='<path d="M12 5v14M5 12h14"/>';
document.querySelectorAll('[data-icon]').forEach(el=>el.innerHTML=icon(el.dataset.icon));
function post(action,value='',extra={}){if(window.Phone)Phone.post(JSON.stringify({action,value,...extra}));}
const escapeHtml=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let state={ready:false,busy:false,loggedIn:false,model:'',effort:'',models:[],threads:[],outbox:[],queuePaused:false,modelsLoading:false,modelsError:''},drawerOpen=false,sheetKind='',sheetData=null,searchTimer,toastTimer;
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
  const catalogChanged=packet.models||(packet.modelsLoading!==undefined&&packet.modelsLoading!==state.modelsLoading)||(packet.modelsError!==undefined&&packet.modelsError!==state.modelsError);
 if(packet.replace){messages.clear();elements.forEach(e=>e.remove());elements.clear();expanded.clear();codeTexts.clear();}
 if(packet.busy&&!wasBusy){metrics.liveTextPaints=metrics.reasoningPaints=metrics.toolPaints=0;metrics.firstLivePaintAt=metrics.lastTurnFinishedAt=0;}
 if(!packet.busy&&wasBusy){metrics.lastTurnFinishedAt=Date.now();metricsUpdate();}
 wasBusy=packet.busy;Object.assign(state,packet);
  if(catalogChanged&&(sheetKind==='model'||sheetKind==='effort'))openSheet(sheetKind,sheetData);
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
 state.follow=beforeBottom;
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
 for(const id of ids){const m=messages.get(id);if(!m)continue;let el=elements.get(id);
  if(!el){el=document.createElement('article');el.dataset.id=id;elements.set(id,el);$('messages').append(el);}
  if(!m.dirty)continue;m.dirty=false;
  el.className='message '+m.kind+(m.phase==='commentary'?' intermediate':'')+(!m.complete&&m.kind==='assistant'?' streaming':'');
   if(m.kind==='assistant')el.innerHTML=`<div class="message-body">${markdown(m.text,id)}</div><div class="message-actions"><button data-copy-message="${escapeHtml(id)}" aria-label="Копировать">${icon('copy')}</button><button data-speak="${escapeHtml(id)}" aria-label="Озвучить">${icon('speaker')}</button></div>`;
   else if(m.kind==='image')el.innerHTML=imageHtml(m.images)+(m.images?.length?'':`<span>${escapeHtml(m.text)}</span>`);
   else if(m.kind==='user')el.innerHTML=imageHtml(m.images)+`<span>${escapeHtml(m.text)}</span>`;
  else if(['reasoning','command','plan'].includes(m.kind)){el.innerHTML=workHtml(m);const d=el.querySelector('details');d.addEventListener('toggle',()=>expanded.set(id,d.open));}
  else el.textContent=m.text;
 }
 document.querySelector('#welcome h1').textContent=state.target==='pc'?'Что сделать на ПК?':'Чем я могу помочь?';
 document.querySelector('.suggestions').hidden=state.target==='pc';
 $('prompt').placeholder=state.target==='pc'?'Спросить Codex на ПК':'Спросить Codex на телефоне';
 $('welcome').hidden=ids.length>0;$('messages').hidden=ids.length===0;
 const active=Array.from(messages.values()).some(m=>!m.complete&&(m.kind==='reasoning'||m.kind==='command'||m.kind==='assistant'&&m.text));
 $('pending').hidden=!state.busy||active;
  const modelName=selectedModel()?.displayName||state.model||'авто';
  $('model-name').textContent='Codex · '+modelName;
  $('composer-model-name').textContent='Модель · '+modelName;
 $('effort-name').textContent=state.effort?'Думать: '+(effortLabels[state.effort]||state.effort):'Размышление';
 $('account-label').textContent=state.loggedIn?(state.account.split(' · ')[0]||'Аккаунт ChatGPT'):'Войти в ChatGPT';
 $('connection').textContent=(state.target==='pc'?'На ПК':'На телефоне')+' · '+(!state.ready?state.status:state.status.includes('Ошибка')?'Ошибка':'Подключено');
  $('connection').classList.toggle('error',state.status.includes('Ошибка'));updateComposer();renderOutbox();
 if(drawerOpen)renderChats();if(state.follow)bottom(true);
 $('to-bottom').hidden=atBottom()||ids.length===0;
}
function updateComposer(){const typed=$('prompt').value.trim().length>0||attachments.length>0;$('send').hidden=!typed;$('voice').hidden=state.busy||typed;$('stop').hidden=!state.busy;$('stop').disabled=!state.ready;$('send').disabled=!state.ready||submitting||attachmentsLoading;$('send').innerHTML=icon('up');$('send').setAttribute('aria-label',state.busy?(deliveryMode==='steer'?'Уточнить текущую задачу':'Добавить в очередь'):'Отправить');$('delivery-button').hidden=!state.busy;$('delivery-name').textContent=deliveryMode==='steer'?'Уточнить сейчас':'В очередь';}
window.setAttachments=packet=>{attachments=packet.images||[];attachmentsLoading=packet.loading;const panel=$('attachments');panel.hidden=!attachments.length&&!attachmentsLoading;panel.innerHTML=attachments.map(i=>`<div class="attachment"><img src="${escapeHtml(i.url)}" alt="Фото"><button data-remove-attachment="${escapeHtml(i.id)}" aria-label="Убрать изображение">${icon('x')}</button></div>`).join('')+(attachmentsLoading?'<span class="attachment-loading">Добавляю фото…</span>':'');updateComposer();};
window.pasteText=packet=>{const p=$('prompt');p.setRangeText(packet.text,p.selectionStart,p.selectionEnd,'end');p.dispatchEvent(new Event('input'));p.focus();};
window.setNativeDraft=packet=>{if($('prompt').value!==packet.text){$('prompt').value=packet.text;resizeInput();updateComposer();}};
function resizeInput(){const p=$('prompt');p.style.height='auto';p.style.height=Math.min(175,p.scrollHeight)+'px';}
function send(){if(submitting||!state.ready||attachmentsLoading)return;if(!state.loggedIn){openSheet('account');return;}const value=$('prompt').value.trim();if(!value&&!attachments.length)return;submitting=true;updateComposer();post('send',value,{mode:state.busy?deliveryMode:'queue'});}
window.submissionResult=packet=>{submitting=false;if(packet.accepted&&$('prompt').value.trim()===packet.value.trim()){$('prompt').value='';post('draft','');resizeInput();}updateComposer();if(packet.accepted){bottom(true);if(state.busy)toast(deliveryMode==='steer'?'Отправляю уточнение…':'Сообщение принято');}};
function renderOutbox(){const signature=JSON.stringify([state.outbox,state.queuePaused,state.busy]);if(signature===outboxSignature)return;outboxSignature=signature;const entries=state.outbox||[];$('outbox').hidden=!entries.length;$('outbox-title').textContent=(state.queuePaused?'Очередь на паузе':'В очереди')+' · '+entries.length;$('queue-toggle').textContent=state.queuePaused?'Продолжить':'Пауза';$('queue-toggle').disabled=!state.ready;const list=$('queued-messages');list.replaceChildren();for(const e of entries){const row=document.createElement('div');row.className='queued-row';const text=document.createElement('div');text.className='queued-text';const label=document.createElement('small');label.textContent=e.status==='sending'?'Отправляю…':e.status==='failed'?e.error:e.mode==='steer'?'Уточнение текущей задачи':'После текущего ответа';const body=document.createElement('span');body.textContent=e.text;text.append(label,body);row.append(text);if(state.busy&&e.status!=='sending'){const steer=document.createElement('button');steer.className='queued-steer';steer.textContent='Сейчас';steer.setAttribute('aria-label','Отправить уточнение сейчас');steer.onclick=()=>post('steerQueued',e.id);row.append(steer);}const remove=document.createElement('button');remove.className='icon-button';remove.innerHTML=icon('x');remove.setAttribute('aria-label','Удалить из очереди');remove.disabled=e.status==='sending';remove.onclick=()=>post('removeQueued',e.id);row.append(remove);list.append(row);}}
function openDrawer(){drawerOpen=true;$('drawer-backdrop').hidden=false;$('drawer').classList.add('open');$('drawer').setAttribute('aria-hidden','false');post('searchChats',$('chat-search').value);renderChats();}
function closeDrawer(){drawerOpen=false;$('drawer-backdrop').hidden=true;$('drawer').classList.remove('open');$('drawer').setAttribute('aria-hidden','true');$('chat-search').blur();}
function renderChats(){const signature=JSON.stringify(state.threads);if(signature===chatSignature)return;chatSignature=signature;$('chat-list').replaceChildren();
 if(!state.threads.length){const empty=document.createElement('div');empty.className='empty-list';empty.textContent=$('chat-search').value?'Ничего не найдено':'Здесь появятся твои чаты';$('chat-list').append(empty);}
 for(const chat of state.threads){const row=document.createElement('div');row.className='chat-row';const open=document.createElement('button');open.textContent=chat.name||chat.preview||'Новый чат';open.addEventListener('click',()=>{if(state.busy||state.outbox.length){toast('Сначала заверши задачу и освободи очередь');return;}post('openChat',chat.id);closeDrawer();});const more=document.createElement('button');more.className='icon-button';more.setAttribute('aria-label','Действия с чатом');more.innerHTML=icon('dots');more.addEventListener('click',()=>openSheet('chat',chat));row.append(open,more);$('chat-list').append(row);}
 $('more-chats').hidden=!state.hasMoreChats;
}
function option(label,action,description='',selected=false,danger=false,whichIcon=''){const b=document.createElement('button');b.className='option'+(selected?' selected':'')+(danger?' danger':'');b.innerHTML=(whichIcon?icon(whichIcon):'')+`<span class="label"><strong>${escapeHtml(label)}</strong>${description?`<small>${escapeHtml(description)}</small>`:''}</span>`+(selected?icon('check'):'');b.addEventListener('click',action);return b;}
function openSheet(kind,data=null){sheetKind=kind;sheetData=data;const c=$('sheet-content');c.replaceChildren();const heading=document.createElement('h2');heading.className='sheet-title';heading.textContent={model:'Модель',effort:'Глубина размышлений',delivery:'Отправка во время задачи',account:'Аккаунт',chat:'Действия с чатом',rename:'Название чата',delete:'Удалить чат?',logout:'Выйти из аккаунта?',devices:'Где работает Codex'}[kind];c.append(heading);
 if(kind==='attach'){heading.textContent='Изображения';c.append(option('Выбрать из галереи',()=>{post('pickImages');closeSheet();},'До 6 изображений'));c.append(option('Вставить из буфера',()=>{post('pasteClipboard');closeSheet();}));c.append(option('Создать изображение',()=>{closeSheet();$('prompt').value='Создай изображение: ';$('prompt').dispatchEvent(new Event('input'));$('prompt').focus();},'Опиши, что нужно нарисовать'));}
 if(kind==='model'){c.append(option('Автоматически',()=>{post('model','');closeSheet();},'Рекомендованная модель',!state.model));for(const m of state.models.filter(x=>!x.hidden))c.append(option(m.displayName||m.model||m.id,()=>{post('model',m.model||m.id);closeSheet();},'',state.model===(m.model||m.id)));if(state.busy){const note=document.createElement('p');note.className='sheet-subtitle';note.textContent='Выбор применяется к новым сообщениям. Уточнение текущей задачи использует её модель.';c.append(note);}c.append(option(state.modelsLoading?'Загружаю модели…':'Обновить список',()=>{if(!state.modelsLoading)post('refreshModels');},state.modelsError||(!state.models.length&&!state.modelsLoading?'Список пока пуст. Проверь подключение и обнови его.':'')));}
 if(kind==='delivery'){for(const [mode,label,description]of[['queue','В очередь','Отправить после завершения текущего ответа'],['steer','Уточнить сейчас','Добавить указание к уже выполняемой задаче']])c.append(option(label,()=>{deliveryMode=mode;closeSheet();updateComposer();},description,deliveryMode===mode));}
 if(kind==='effort'){c.append(option('Автоматически',()=>{post('effort','');closeSheet();},'Рекомендуемый уровень модели',!state.effort));for(const e of selectedModel()?.supportedReasoningEfforts||[])c.append(option(effortLabels[e.reasoningEffort]||e.reasoningEffort,()=>{post('effort',e.reasoningEffort);closeSheet();},effortDetails[e.reasoningEffort]||e.description,state.effort===e.reasoningEffort));}
 if(kind==='account'){
  const sub=document.createElement('p');sub.className='sheet-subtitle';sub.textContent=state.loggedIn?state.account:'Вход через твой аккаунт ChatGPT';c.append(sub);
  if(!state.loggedIn&&state.target!=='pc'){if(state.loginPending)c.append(option('Продолжить вход',()=>{post('continueLogin');closeSheet();}));c.append(option('Войти через браузер',()=>{post('loginBrowser');closeSheet();}));c.append(option('Войти по коду',()=>{post('loginDevice');closeSheet();}));}
  c.append(option('Устройства',()=>openSheet('devices'),state.target==='pc'?'Сейчас: ПК':'Сейчас: этот телефон',false,false,'phone'));
   c.append(option('Уведомления о задачах',()=>{post('notificationSettings');closeSheet();},'Настройки Android: звук, шторка и экран блокировки'));
  c.append(option(state.target==='pc'?'Проверить подключение ПК':'Проверить телефон и Docker',()=>{post('diagnostics');closeSheet();},state.target==='pc'?'Прочитать имя ПК и рабочую папку':'Команды выполняются на этом телефоне',false,false,'phone'));
  if(state.loggedIn&&state.target!=='pc')c.append(option('Выйти из аккаунта',()=>openSheet('logout'),' ',false,false));
  c.append(option('Остановить помощника',()=>{post('stopEngine');closeSheet();},'Контейнеры продолжат работать'));
 }
 if(kind==='devices'){
  c.append(option('Этот телефон',()=>{if(state.busy){toast('Сначала дождись завершения ответа');return;}post('connectPhone');closeSheet();},'Android, root и Docker',state.target!=='pc',false,'phone'));
  c.append(option('Мой ПК',()=>{if(state.busy){toast('Сначала дождись завершения ответа');return;}if(!state.pcConfigured){post('pcSettings');return;}post('connectPc');closeSheet();},'Файлы и команды ПК через SSH',state.target==='pc',false,'box'));
  c.append(option('Настроить подключение ПК',()=>{post('pcSettings');closeSheet();},'Адрес компьютера и публичный ключ'));
  const note=document.createElement('p');note.className='sheet-subtitle';note.textContent='Чаты телефона и отдельные чаты Mobile на ПК хранятся на своих устройствах. ПК должен быть включён и доступен по сети.';c.append(note);
 }
 if(kind==='chat'){c.append(option('Переименовать',()=>openSheet('rename',data),'',false,false,'rename'));c.append(option('Архивировать',()=>{post('archiveChat',data.id);closeSheet();},'',false,false,'archive'));c.append(option('Удалить чат',()=>openSheet('delete',data),'',false,true,'trash'));}
 if(kind==='rename'){const input=document.createElement('input');input.className='sheet-input';input.value=data.name||data.preview||'';c.append(input);const buttons=document.createElement('div');buttons.className='sheet-buttons';const cancel=document.createElement('button');cancel.textContent='Отмена';cancel.onclick=closeSheet;const save=document.createElement('button');save.className='confirm';save.textContent='Сохранить';save.onclick=()=>{post('renameChat',input.value,{id:data.id});closeSheet();};buttons.append(cancel,save);c.append(buttons);setTimeout(()=>input.focus(),180);}
 if(kind==='delete'||kind==='logout'){const p=document.createElement('p');p.className='sheet-subtitle';p.textContent=kind==='delete'?'Локальная история этого чата будет удалена.':'Для новых запросов потребуется снова войти.';c.append(p);const buttons=document.createElement('div');buttons.className='sheet-buttons';for(const [label,action,cls]of[['Отмена',closeSheet,''],[kind==='delete'?'Удалить':'Выйти',()=>{post(kind==='delete'?'deleteChat':'logout',data?.id||'');closeSheet();},'danger']]){const b=document.createElement('button');b.textContent=label;b.className=cls;b.onclick=action;buttons.append(b);}c.append(buttons);}
 $('sheet-backdrop').hidden=false;$('sheet').classList.add('open');$('sheet').setAttribute('aria-hidden','false');
}
function closeSheet(){sheetKind='';$('sheet-backdrop').hidden=true;$('sheet').classList.remove('open');$('sheet').setAttribute('aria-hidden','true');document.activeElement?.blur();}
window.handleBack=()=>{if(sheetKind){closeSheet();return true;}if(drawerOpen){closeDrawer();return true;}return false;};
$('menu').onclick=openDrawer;$('close-drawer').onclick=closeDrawer;$('drawer-backdrop').onclick=closeDrawer;$('sheet-backdrop').onclick=closeSheet;
function newChat(){if(state.busy||state.outbox.length){toast('Сначала заверши задачу и освободи очередь');return;}post('newChat');closeDrawer();$('prompt').value='';post('draft','');resizeInput();}
$('new-chat').onclick=newChat;$('drawer-new').onclick=newChat;$('model-button').onclick=$('composer-model').onclick=()=>{openSheet('model');if(!state.models.length&&!state.modelsLoading)post('refreshModels');};$('effort-button').onclick=()=>openSheet('effort');$('delivery-button').onclick=()=>openSheet('delivery');$('account-button').onclick=()=>openSheet('account');$('connection').onclick=()=>openSheet('devices');$('stop').onclick=()=>post('stopTurn');$('queue-toggle').onclick=()=>post(state.queuePaused?'resumeQueue':'pauseQueue');
$('send').onclick=send;$('voice').onclick=()=>post('voice');$('mic').onclick=()=>post('mic');$('more-chats').onclick=()=>post('moreChats');$('to-bottom').onclick=()=>bottom(true);
$('attach').onclick=()=>openSheet('attach');$('attachments').addEventListener('click',e=>{const b=e.target.closest('[data-remove-attachment]');if(b)post('removeAttachment',b.dataset.removeAttachment);});
$('prompt').addEventListener('input',()=>{resizeInput();updateComposer();post('draft',$('prompt').value);});$('prompt').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing&&!matchMedia('(pointer:coarse)').matches){e.preventDefault();send();}});
$('scroll').addEventListener('scroll',()=>{$('to-bottom').hidden=atBottom()||messages.size===0;},{passive:true});
$('chat-search').addEventListener('input',()=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>post('searchChats',$('chat-search').value),250);});
document.querySelectorAll('[data-prompt]').forEach(b=>b.onclick=()=>{$('prompt').value=b.dataset.prompt;resizeInput();updateComposer();post('draft',b.dataset.prompt);$('prompt').focus();});
$('messages').addEventListener('click',e=>{
 const link=e.target.closest('a[data-link]');if(link){e.preventDefault();if(linkTarget(link.dataset.link))post('openLink',link.dataset.link);return;}
 const image=e.target.closest('[data-image]');if(image){post('openImage',image.dataset.image);return;}
 const code=e.target.closest('[data-code]');if(code){post('copyText',codeTexts.get(code.dataset.code)||'');toast('Скопировано');return;}
 const copy=e.target.closest('[data-copy-message]');if(copy){post('copyMessage',copy.dataset.copyMessage);toast('Скопировано');return;}
 const speak=e.target.closest('[data-speak]');if(speak)post('speakMessage',speak.dataset.speak);
});
resizeInput();updateComposer();
