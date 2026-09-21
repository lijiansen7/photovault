"""不用 Docker 时的启动方式：python run.py """

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

if __name__ == "__main__":
    import uvicorn

    os.environ.setdefault("DATA_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "data"))
    uvicorn.run("app.main:app", host="0.0.0.0",
                port=int(os.getenv("PORT", "8765")), reload=False)
