import json, urllib.request, urllib.parse, time
API="http://127.0.0.1:1122"
def post(ep, obj, timeout=25):
    data=json.dumps(obj, ensure_ascii=False).encode("utf-8")
    req=urllib.request.Request(API+ep, data=data, headers={"Content-Type":"application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception as e:
        return {"isSuccess":False,"errorMsg":str(e)}

d=json.load(open("cur.json",encoding="utf-8"))["data"]
loc=[b for b in d if str(b.get("bookUrl","")).startswith("content")]
already={b["bookUrl"] for b in d if str(b.get("bookUrl","")).startswith("/storage")}
ok_save=ok_del=fail=0
failures=[]
for i,b in enumerate(loc,1):
    p=urllib.parse.unquote(b["bookUrl"].split("/document/",1)[-1]).split(":",1)[1]
    plain="/storage/emulated/0/"+p
    nb=dict(b); nb["bookUrl"]=plain
    if plain not in already:
        r=post("/saveBook", nb)
        if r.get("isSuccess"): ok_save+=1
        else: fail+=1; failures.append(("save",b["name"],r.get("errorMsg")))
    r2=post("/deleteBook", b)
    if r2.get("isSuccess"): ok_del+=1
    else: fail+=1; failures.append(("del",b["name"],r2.get("errorMsg")))
    if i%60==0: print(f"  进度 {i}/{len(loc)}  save={ok_save} del={ok_del} fail={fail}", flush=True)
print(f"完成: 新建={ok_save} 删旧={ok_del} 失败={fail}")
for f in failures[:8]: print("  失败:", f)
json.dump(failures, open("batch-failures.json","w",encoding="utf-8"), ensure_ascii=False)
