/* ... placeholder for auth-context ... */
import { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { api } from '../api';

interface User {
  id: number;
  username: string;
  role: string;
  real_name?: string;
  phone?: string;
}

interface AuthContextType {
  user: User | null;
  token: string | null;
  login: (username: string, password: string) => Promise<void>;
  register: (data: any) => Promise<void>;
  logout: () => void;
  loading: boolean;
}

const AuthContext = createContext<AuthContextType>(null!);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(localStorage.getItem('token'));
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (token) {
      api.setToken(token);
      api.get('/auth/me').then(data => {
        setUser(data.user);
      }).catch(() => {
        localStorage.removeItem('token');
        setToken(null);
      }).finally(() => setLoading(false));
    } else {
      setLoading(false);
    }
  }, [token]);

  const login = async (username: string, password: string) => {
    const data = await api.post('/auth/login', { username, password });
    localStorage.setItem('token', data.token);
    api.setToken(data.token);
    setToken(data.token);
    setUser(data.user);
  };

  const register = async (regData: any) => {
    const data = await api.post('/auth/register', regData);
    localStorage.setItem('token', data.token);
    api.setToken(data.token);
    setToken(data.token);
    setUser(data.user);
  };

  const logout = () => {
    localStorage.removeItem('token');
    api.setToken(null);
    setToken(null);
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, token, login, register, logout, loading }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
