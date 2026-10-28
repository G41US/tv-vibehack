const {createAvatar}=require('@dicebear/core');const c=require('@dicebear/collection');
const {chromium}=require('playwright');const fs=require('fs');
const skins=['614335','ae5d29','d08b5b','edb98a','fd9841','ffdbb4','614335','ae5d29'];
const tops=['fro','shortCurly','bob','dreads01','curly','theCaesar','bun','straight01','shortFlat','frida','froBand','shortWaved','bigHair','hijab'];
const list=[];
tops.forEach((t,i)=>list.push(['avataaars',{seed:'p'+i,top:[t],skinColor:[skins[i%skins.length]],mouth:['smile','twinkle','default'][i%3].split(),eyes:[['happy','default','wink','squint'][i%4]],eyebrows:['defaultNatural','default','raisedExcited'][i%3].split(),accessoriesProbability:i%5==3?100:0,accessories:['prescription02','round','kurt'],facialHairProbability:i==5||i==10?100:0,topProbability:100}]));
['happy','glow','round','eva','hearts','roundFrame02','shade01','sensor'].forEach((e,i)=>list.push(['bottts',{seed:'r'+i,eyes:[e],mouth:[['smile01','smile02','grill01','square01'][i%4]]}]));
['laughing','smile','smirk','laughing','smile','smirk','laughing','smile'].forEach((m,i)=>list.push(['micah',{seed:'m'+i,mouth:[m],baseColor:[['77311d','ac6651','f9c9b6'][i%3]]}]));
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:256,height:256}});
let sheet='<body style="margin:0;background:#334;display:flex;flex-wrap:wrap;width:1300px">';
for(let i=0;i<list.length;i++){const [s,o]=list[i];const uri=createAvatar(c[s],{size:256,...o}).toDataUri();
await p.setContent(`<body style="margin:0;background:transparent"><img src="${uri}" width=256 height=256>`);await p.waitForTimeout(50);
await p.screenshot({path:`out/av_${String(i+1).padStart(2,'0')}.png`,omitBackground:true});
sheet+=`<div style="margin:4px;width:120px;height:120px;border-radius:24px;background:linear-gradient(135deg,#5B6CFF,#9A5BFF)"><img src="${uri}" width=120></div>`}
const q=await b.newPage({viewport:{width:1300,height:300}});await q.setContent(sheet);await q.waitForTimeout(300);await q.screenshot({path:'final.png',fullPage:true});await b.close()})();
