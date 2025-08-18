import { createRoute as createRoute_1 } from "@vaadin/hilla-file-router/runtime.js";
import type { AgnosticRoute as AgnosticRoute_1, RouteModule as RouteModule_1 } from "@vaadin/hilla-file-router/types.js";
import { lazy as lazy_1 } from "react";
import * as Page_1 from "../views/@index.js";
import * as Layout_1 from "../views/@layout.js";
const routes: readonly AgnosticRoute_1[] = [
    createRoute_1("", Layout_1.default, (Layout_1 as RouteModule_1).config, [
        createRoute_1("", Page_1.default, (Page_1 as RouteModule_1).config),
        createRoute_1("control", lazy_1(() => import("../views/control.js")), { "route": "control", "menu": { "order": 10, "icon": "line-awesome/svg/cogs-solid.svg", "exclude": true }, "title": "Control board", "loginRequired": true, "flowLayout": false }),
        createRoute_1("result", [
            createRoute_1(":resultId", [
                createRoute_1("tracking", lazy_1(() => import("../views/result/{resultId}/tracking.js")), { "route": "tracking", "menu": { "order": 2, "icon": "line-awesome/svg/file.svg", "exclude": true }, "title": "Cam object tracking", "loginRequired": true, "flowLayout": false })
            ])
        ]),
        createRoute_1("run", lazy_1(() => import("../views/run.js")), { "route": "run", "menu": { "order": 10, "icon": "line-awesome/svg/play-circle-solid.svg" }, "title": "Execute test", "loginRequired": true, "flowLayout": false })
    ])
];
export default routes;
