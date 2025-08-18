import {UserConfigFn} from 'vite';
import {overrideVaadinConfig} from './vite.generated';
import customFileSystemRouterPlugin from "./customFileSystemRouterPlugin";

function forceMainNodeModules() {
    return {
        name: 'force-main-node-modules',
        enforce: 'pre' as const, // Fixed type issue by using 'as const'
        async resolveId(source: string, importer: string, options) {
            if (importer && importer.includes('/cms/src')) {
                const resolved = await this.resolve(source, importer, {
                    ...options,
                    skipSelf: true,
                });

                if (resolved && resolved.id.includes('/cms/node_modules/')) {
                    resolved.id = resolved.id.replace('/cms/node_modules/', '/command-deck/node_modules/');
                    return resolved;
                }
            }

            return null;
        },
    };
}

const customConfig: UserConfigFn = (env) => ({
    resolve: {
        alias: {
            'cms': __dirname + '/../cms/src/main/frontend',
        }
    },
    build: {
        rollupOptions: {
            external: id => id.includes('/cms/node_modules/')
        }
    },

    plugins: [
        forceMainNodeModules(),
        customFileSystemRouterPlugin(env.mode === 'development')
    ],
});

export default overrideVaadinConfig(customConfig);