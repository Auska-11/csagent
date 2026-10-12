# 闲鱼侧车（BRIDGE 模式）：仅协议层依赖，python:3.11-slim 即可运行。
# cookie 与服务令牌不进镜像，通过 compose env_file（sidecar/xianyu/.env）注入。
FROM python:3.11-slim

WORKDIR /app

ENV TZ=Asia/Shanghai \
    PYTHONUNBUFFERED=1 \
    PYTHONUTF8=1

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py client.py protocol.py delivery.py XianyuApis.py LICENSE README.md PROVENANCE.md ./
COPY utils ./utils

# 进程内自动重连（main 循环），容器层面只需保证前台运行与自动重启。
CMD ["python", "main.py"]
