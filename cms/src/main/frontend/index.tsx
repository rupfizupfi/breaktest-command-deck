import { router } from 'Frontend/generated/routes.js';
import { AuthProvider } from 'cms/util/auth';
import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router';
import './model/init';

function App() {
  return (
    <AuthProvider>
      <RouterProvider router={router} />
    </AuthProvider>
  );
}

createRoot(document.getElementById('outlet')!).render(createElement(App));
