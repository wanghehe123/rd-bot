/* RiderLayout */
import { useState, useEffect } from 'react';
import { Layout, Menu, Badge, Dropdown, Avatar, message } from 'antd';
import {
  EnvironmentOutlined,
  OrderedListOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import api from '../api';

const { Header, Sider, Content } = Layout;

export default function RiderLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [pendingCount, setPendingCount] = useState(0);

  useEffect(() => {
    loadPendingCount();
    const timer = setInterval(loadPendingCount, 30000);
    return () => clearInterval(timer);
  }, []);

  const loadPendingCount = async () => {
    try {
      const data = await api.getRiderAvailable();
      setPendingCount(data.orders?.length || 0);
    } catch {}
  };

  const menuItems = [
    {
      key: '/rider/available',
      icon: <EnvironmentOutlined />,
      label: (
        <Badge count={pendingCount} size="small" offset={[8, 0]}>
          <span style={{ marginRight: 16 }}>待接订单</span>
        </Badge>
      ),
    },
    {
      key: '/rider/deliveries',
      icon: <OrderedListOutlined />,
      label: '我的配送',
    },
  ];

  const userMenuItems = [
    {
      key: 'profile',
      icon: <UserOutlined />,
      label: `${user?.name || '骑手'}`,
      disabled: true,
    },
    { type: 'divider' as const },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: () => {
        logout();
        navigate('/login');
      },
    },
  ];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsedWidth="0">
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fff',
            fontSize: 18,
            fontWeight: 'bold',
          }}
        >
          🏍️ 骑手端
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            justifyContent: 'flex-end',
            alignItems: 'center',
          }}
        >
          <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
            <Avatar
              style={{ backgroundColor: '#fa8c16', cursor: 'pointer' }}
              icon={<UserOutlined />}
            />
          </Dropdown>
        </Header>
        <Content style={{ margin: 24 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
