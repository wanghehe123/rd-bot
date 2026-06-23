import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import Login from './pages/Login';
import CustomerLayout from './layouts/CustomerLayout';
import MerchantLayout from './layouts/MerchantLayout';
import RiderLayout from './layouts/RiderLayout';
import AdminLayout from './layouts/AdminLayout';
import CustomerHome from './pages/customer/Home';
import CustomerOrders from './pages/customer/Orders';
import MerchantDashboard from './pages/merchant/Dashboard';
import MerchantMenu from './pages/merchant/Menu';
import MerchantOrders from './pages/merchant/Orders';
import RiderAvailable from './pages/rider/Available';
import RiderMyDeliveries from './pages/rider/MyDeliveries';
import AdminDashboard from './pages/admin/Dashboard';
import AdminMerchants from './pages/admin/Merchants';
import AdminOrders from './pages/admin/Orders';
import AdminRiders from './pages/admin/Riders';
import AdminAccounts from './pages/admin/Accounts';

function ProtectedRoute({ children, role }: { children: React.ReactNode; role?: string }) {
  const { user, token } = useAuth();
  if (!token) return <Navigate to="/login" />;
  if (role && user?.role !== role) return <Navigate to="/login" />;
  return <>{children}</>;
}

export default function App() {
  const { user, token } = useAuth();

  const getDefaultRoute = () => {
    if (!token || !user) return '/login';
    switch (user.role) {
      case 'customer': return '/customer';
      case 'merchant': return '/merchant';
      case 'rider': return '/rider';
      case 'admin': return '/admin';
      default: return '/login';
    }
  };

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={token ? <Navigate to={getDefaultRoute()} /> : <Login />} />

        <Route path="/customer" element={<ProtectedRoute role="customer"><CustomerLayout /></ProtectedRoute>}>
          <Route index element={<CustomerHome />} />
          <Route path="orders" element={<CustomerOrders />} />
        </Route>

        <Route path="/merchant" element={<ProtectedRoute role="merchant"><MerchantLayout /></ProtectedRoute>}>
          <Route index element={<MerchantDashboard />} />
          <Route path="menu" element={<MerchantMenu />} />
          <Route path="orders" element={<MerchantOrders />} />
        </Route>

        <Route path="/rider" element={<ProtectedRoute role="rider"><RiderLayout /></ProtectedRoute>}>
          <Route index element={<RiderAvailable />} />
          <Route path="deliveries" element={<RiderMyDeliveries />} />
        </Route>

        <Route path="/admin" element={<ProtectedRoute role="admin"><AdminLayout /></ProtectedRoute>}>
          <Route index element={<AdminDashboard />} />
          <Route path="merchants" element={<AdminMerchants />} />
          <Route path="orders" element={<AdminOrders />} />
          <Route path="riders" element={<AdminRiders />} />
          <Route path="accounts" element={<AdminAccounts />} />
        </Route>

        <Route path="*" element={<Navigate to={getDefaultRoute()} />} />
      </Routes>
    </BrowserRouter>
  );
}
