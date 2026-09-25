import { RouterConfigurationBuilder } from '@vaadin/hilla-file-router/runtime.js';
import Flow from 'Frontend/generated/flow/Flow';
import fileRoutes from 'Frontend/generated/file-routes';
import cmsFileRoutes from "cms/generated/file-routes";

export const {router, routes} = new RouterConfigurationBuilder()
    .withFileRoutes(cmsFileRoutes)
    .withFileRoutes(fileRoutes)
    .withFallback(Flow)
    .protect()
    .build();
