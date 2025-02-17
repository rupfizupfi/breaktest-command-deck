import { UserConfigFn } from 'vite';
import { overrideVaadinConfig } from './vite.generated';

const customConfig: UserConfigFn = (env) => ({
    resolve: {
        alias: {
            'cms': __dirname + '/src/main/frontend',
        }
    },
});

export default overrideVaadinConfig(customConfig);
