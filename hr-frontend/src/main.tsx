// Design system first: it declares the cascade-layer order every later stylesheet slots into.
import './design/fonts'
import './design/index.css'
import './styles/legacy.css'
import React from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { isApiError } from './api/client'
import { AuthProvider } from './auth/AuthContext'
import { NoticeProvider } from './components/Notices'
import { initTheme } from './design/theme'
import { ToastProvider } from './design/components'
import { routerBasename } from './design/lib/links'

initTheme()

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      refetchOnWindowFocus: false,
      // Never retry client errors (403/404/409…); retry transient failures once.
      retry: (failureCount, error) => !(isApiError(error) && error.status >= 400 && error.status < 500) && failureCount < 1,
    },
    mutations: { retry: false },
  },
})

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      {/* /hr behind the gateway, / on its own port: the Vite base (vite.config.ts) decides both. */}
      <BrowserRouter basename={routerBasename(import.meta.env.BASE_URL)} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <ToastProvider>
          <NoticeProvider>
            <AuthProvider>
              <App />
            </AuthProvider>
          </NoticeProvider>
        </ToastProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
)
