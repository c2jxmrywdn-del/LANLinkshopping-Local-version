FROM docker.m.daocloud.io/library/python:3.12-alpine
WORKDIR /app
COPY feishu_adapter.py .
EXPOSE 8061
CMD ["python", "-u", "feishu_adapter.py"]
