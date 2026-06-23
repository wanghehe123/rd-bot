/* AdminLayout */
import { Layout, Menu, Dropdown, Avatar } from 'antd';
import {
  DashboardOutlined,
  ShopOutlined,
  OrderedListOutlined,
  CarOutlined,
  TeamOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

const { Header, Sider, Content } = Layout;

export default function AdminLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const menuItems = [
    { key: '/admin/dashboard', icon: <DashboardOutlined />, label: '数据概览' },
    { key: '/admin/merchants', icon: <ShopOutlined />, label: '商家管理' },
    { key: '/admin/orders', icon: <OrderedListOutlined />, label: '订单管理' },
    { key: '/admin/riders', icon: <CarOutlined />, label: '骑手管理' },
    { key: '/admin/accounts', icon: <TeamOutlined />, label: '账号管理' },
  ];

  const userMenuItems = [
    { key: 'profile', icon: <UserOutlined />, label: `${user?.name || '管理员'}`, disabled: true },
    { type: 'divider' as const },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: () => { logout(); navigate('/login'); },
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
          ⚙️ 管理后台
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
            <Avatar style={{ backgroundColor: '#f5222d', cursor: 'pointer' }} icon={<UserOutlined />} />
          </Dropdown>
        </Header>
        <Content style={{ margin: 24 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
