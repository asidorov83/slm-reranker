import urllib.request, json
url = "https://api.github.com/repos/android/ai-samples/issues/3/comments"
req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
try:
    data = json.loads(urllib.request.urlopen(req).read().decode("utf-8"))
    for comment in data:
        print("--- COMMENT ---")
        print(comment.get("body"))
except Exception as e:
    pass
