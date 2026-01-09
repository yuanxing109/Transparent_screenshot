#!/usr/bin/env python3

import requests
import sys
import os

def check_url(url):
    try:
        response = requests.head(url, timeout=10)
        return response.status_code == 200
    except:
        return False

def main():
    dependencies = {
        "Xposed API": "https://api.xposed.info/",
        "JitPack": "https://jitpack.io/",
        "Google Maven": "https://maven.google.com/",
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