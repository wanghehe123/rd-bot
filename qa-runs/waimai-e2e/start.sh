#!/bin/bash
set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}🍜 外卖平台启动脚本${NC}"
echo "===================="

# Install server dependencies
echo -e "\n${YELLOW}[1/4] 安装后端依赖...${NC}"
cd server
npm install
echo -e "${GREEN}✓ 后端依赖安装完成${NC}"

# Install client dependencies
echo -e "\n${YELLOW}[2/4] 安装前端依赖...${NC}"
cd ../client
npm install
echo -e "${GREEN}✓ 前端依赖安装完成${NC}"

# Seed database
echo -e "\n${YELLOW}[3/4] 初始化数据库...${NC}"
cd ../server
npx ts-node src/seed.ts
echo -e "${GREEN}✓ 数据库初始化完成${NC}"

# Start backend
echo -e "\n${YELLOW}[4/4] 启动服务...${NC}"
cd ../server
npx ts-node src/index.ts &
SERVER_PID=$!
echo -e "${GREEN}✓ 后端服务已启动 (PID: $SERVER_PID)${NC}"

# Start frontend
cd ../client
npx vite --host &
CLIENT_PID=$!
echo -e "${GREEN}✓ 前端服务已启动 (PID: $CLIENT_PID)${NC}"

echo -e "\n${GREEN}====================${NC}"
echo -e "${GREEN}🚀 外卖平台已启动！${NC}"
echo -e "${GREEN}  前端: http://localhost:5173${NC}"
echo -e "${GREEN}  后端: http://localhost:3000${NC}"
echo -e "${GREEN}====================${NC}"
echo -e "${YELLOW}按 Ctrl+C 停止服务${NC}"

# Wait for background processes
trap "kill $SERVER_PID $CLIENT_PID 2>/dev/null; exit" SIGINT SIGTERM
wait
