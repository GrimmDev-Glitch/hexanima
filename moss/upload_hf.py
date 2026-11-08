import os, time
from huggingface_hub import HfApi
api = HfApi(token=os.environ["HF_TOKEN"]); rid = "ayanoby/hexanima-moss-packs"
api.create_repo(rid, repo_type="model", private=True, exist_ok=True); print("repo ready", rid, flush=True)
files = []
for root, _, fs in os.walk("dist"):
    for f in fs:
        p = os.path.join(root, f); files.append((os.path.relpath(p, "dist"), p))
# small files first, manifest last
files.sort(key=lambda t: (t[0] == "manifest.json", os.path.getsize(t[1])))
have = {}
try: have = {i.path: i.size for i in api.list_repo_tree(rid, recursive=True) if hasattr(i, "size")}
except Exception as e: print("list failed", e)
done = 0
for rel, p in files:
    sz = os.path.getsize(p)
    if have.get(rel) == sz: done += sz; continue
    for attempt in range(6):
        try:
            t0 = time.time(); api.upload_file(path_or_fileobj=p, path_in_repo=rel, repo_id=rid, repo_type="model", commit_message="add " + rel); break
        except Exception as e:
            print("retry", rel, attempt, str(e)[:120], flush=True); time.sleep(10 * (attempt + 1))
    else: print("FAILED", rel, flush=True); raise SystemExit(1)
    done += sz; print(f"{rel} {sz/1e6:.0f} MB ({time.time()-t0:.0f}s) total {done/1e9:.1f} GB", flush=True)
print("UPLOAD DONE", flush=True)
