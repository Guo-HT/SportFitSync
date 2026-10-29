#!/usr/bin/env python3
"""逐屏取 UI 树 + 截图，做对比度/重叠审计（uiautomator 与截图必须同一时刻）。"""
import re, subprocess, time, xml.etree.ElementTree as ET
from PIL import Image
from collections import Counter
ADB = ["adb"]
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")

def sh(*a): subprocess.run([*ADB, *a], capture_output=True)
def tap(x, y): sh("shell","input","tap",str(x),str(y)); time.sleep(1.2)
def swipe(x1,y1,x2,y2): sh("shell","input","swipe",str(x1),str(y1),str(x2),str(y2),"300"); time.sleep(1.0)

def lum(c):
    def f(v):
        v/=255
        return v/12.92 if v<=0.03928 else ((v+0.055)/1.055)**2.4
    return 0.2126*f(c[0])+0.7152*f(c[1])+0.0722*f(c[2])
def ratio(a,b):
    la,lb=lum(a),lum(b); hi,lo=max(la,lb),min(la,lb); return (hi+0.05)/(lo+0.05)

def audit(name):
    sh("shell","uiautomator","dump","/sdcard/ui.xml")
    xml = subprocess.run([*ADB,"shell","cat","/sdcard/ui.xml"],capture_output=True,text=True).stdout
    sh("shell","screencap","-p","/sdcard/ui.png")
    subprocess.run([*ADB,"pull","/sdcard/ui.png",f"/tmp/audit_{name}.png"],capture_output=True)
    im = Image.open(f"/tmp/audit_{name}.png").convert("RGB")
    root = ET.fromstring(xml); nodes=[]
    for n in root.iter("node"):
        t=(n.get("text") or "").strip()
        if not t: continue
        m=BOUNDS.search(n.get("bounds",""))
        if not m: continue
        x1,y1,x2,y2=map(int,m.groups())
        if x2-x1<4 or y2-y1<4: continue
        if x2>im.width or y2>im.height: continue
        nodes.append((t,x1,y1,x2,y2))
    bad=[];overlap=[]
    for t,x1,y1,x2,y2 in nodes:
        crop=im.crop((x1,y1,x2,y2)); cols=Counter(crop.getdata())
        bg=cols.most_common(1)[0][0]
        cands=sorted(cols, key=lambda c: lum(c))
        dark, light = cands[0], cands[-1]
        fg = dark if ratio(dark,bg) >= ratio(light,bg) else light
        r=ratio(fg,bg)
        if r<4.5: bad.append((round(r,2),t[:24],f"#{fg[0]:02X}{fg[1]:02X}{fg[2]:02X}",f"#{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}"))
    for i in range(len(nodes)):
        for j in range(i+1,len(nodes)):
            a,b=nodes[i],nodes[j]
            ox=min(a[3],b[3])-max(a[1],b[1]); oy=min(a[4],b[4])-max(a[2],b[2])
            if ox>8 and oy>8: overlap.append((a[0][:16],b[0][:16],ox,oy))
    print(f"\n=== {name}（{len(nodes)} 文本节点）===")
    if bad:
        print("  ⚠ 对比度<4.5：")
        for r,t,fg,bg in sorted(bad)[:10]: print(f"    {r:5}  「{t}」 字{fg} 底{bg}")
    else: print("  ✓ 对比度全部 ≥4.5")
    if overlap:
        print("  ⚠ 文本重叠：")
        for a,b,ox,oy in overlap[:6]: print(f"    「{a}」×「{b}」 {ox}x{oy}px")
    else: print("  ✓ 无重叠")

sh("shell","cmd","uimode","night","no"); time.sleep(2)
tap(231,2952); audit("home")
tap(720,2952); audit("history")
tap(1209,2952); swipe(720,1200,720,2600); audit("settings_top")
tap(387,636); audit("accounts")
tap(112,256); swipe(720,2400,720,1200); tap(393,2455); audit("cache")
tap(112,256); tap(231,2952); tap(762,1127); tap(1095,1901); time.sleep(4); audit("picker")
