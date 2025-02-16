import { UserConfigFn } from 'vite';
import { overrideVaadinConfig } from './vite.generated';

const customConfig: UserConfigFn = (env) => ({
    resolve: {
        alias: {
            'cms-frontend': '../cms/src/main/frontend',
        }
    },
});

console.error(__dirname);

export default overrideVaadinConfig(customConfig);
