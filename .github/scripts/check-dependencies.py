#!/usr/bin/env python3

import requests
import sys

def check_url(url):
    try:
        # 使用 GET 而不是 HEAD，因为部分服务器不支持 HEAD
        response = requests.get(url, timeout=10, allow_redirects=True)
        return response.status_code == 200
    except Exception as e:
        print(f"Error: {e}")
        return False

def main():
    dependencies = {
        "Xposed API": "https://api.xposed.info/",
        "JitPack": "https://jitpack.io/",
        "Google Maven": "https://dl.google.com/dl/android/maven2/",
        "Maven Central": "https://repo.maven.apache.org/maven2/"
    }
    
    all_ok = True
    for name, url in dependencies.items():
        print(f"Checking {name} ({url})...", end=" ")
        if check_url(url):
            print("OK")
        else:
            print("FAILED")
            all_ok = False
    
    return 0 if all_ok else 1

if __name__ == "__main__":
    sys.exit(main())
