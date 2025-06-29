import { router } from 'Frontend/routes';
import { AuthProvider } from 'cms/util/auth';
import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router';
import 'cms/model/init';

function App() {
    return (<AuthProvider><RouterProvider router={router} /></AuthProvider>);
}

createRoot(document.getElementById('outlet')!).render(createElement(App));
