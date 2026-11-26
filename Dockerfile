FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 SUDOAI_DATA=/data SUDOAI_MEDIA=/media
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates && rm -rf /var/lib/apt/lists/* && groupadd -g 1000 sudoai && useradd -u 1000 -g 1000 -M sudoai && install -d -m 0700 -o 1000 -g 1000 /data /media
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY sudoai/ sudoai/
USER 1000:1000
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=4s --start-period=15s CMD ["python","-c","import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz',timeout=3).read()"]
CMD ["uvicorn","sudoai.main:app","--host","0.0.0.0","--port","8000","--workers","1","--no-access-log"]
