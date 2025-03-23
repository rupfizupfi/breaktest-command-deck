import { UserConfigFn } from 'vite';
import { overrideVaadinConfig } from './vite.generated';

const customConfig: UserConfigFn = (env) => ({
    resolve: {
        dedupe: ['react', 'react-dom', '@vaadin', '@stomp', '@polymer', 'polymer', 'lit', 'preact', 'react-is']
        alias: {
            'cms': __dirname + '/../cms/src/main/frontend',
        }
    },
    optimizeDeps: {
        exclude: [__dirname + '/../cms/node_modules']
    }
});

export default overrideVaadinConfig(customConfig);
