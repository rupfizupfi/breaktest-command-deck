import { createRoute as createRoute_1 } from "@vaadin/hilla-file-router/runtime.js";
import type { AgnosticRoute as AgnosticRoute_1, RouteModule as RouteModule_1 } from "@vaadin/hilla-file-router/types.js";
import { lazy as lazy_1 } from "react";
import * as Page_1 from "../views/@index.js";
import * as Layout_1 from "../views/@layout.js";
import * as Page_2 from "../views/login.js";
const routes: readonly AgnosticRoute_1[] = [
    createRoute_1("", Layout_1.default, (Layout_1 as RouteModule_1).config, [
        createRoute_1("", Page_1.default, (Page_1 as RouteModule_1).config),
        createRoute_1("admin", [
            createRoute_1("user", lazy_1(() => import("../views/admin/user.js")), { "route": "user", "menu": { "order": 1, "icon": "line-awesome/svg/user.svg", "exclude": true }, "title": "User", "loginRequired": true, "flowLayout": false })
        ]),
        createRoute_1("customer", lazy_1(() => import("../views/customer.js")), { "route": "customer", "menu": { "order": 1, "icon": "line-awesome/svg/file.svg" }, "title": "Customer", "loginRequired": true, "flowLayout": false }),
        createRoute_1("login", Page_2.default, (Page_2 as RouteModule_1).config),
        createRoute_1("project", [
            createRoute_1("", lazy_1(() => import("../views/project/@index.js")), { "route": "", "menu": { "order": 2, "icon": "line-awesome/svg/file.svg" }, "title": "Projects", "loginRequired": true, "flowLayout": false }),
            createRoute_1(":projectId", [
                createRoute_1("sample", lazy_1(() => import("../views/project/{projectId}/sample.js")), { "route": "sample", "menu": { "order": 2, "icon": "line-awesome/svg/file.svg", "exclude": true }, "title": "Sample", "loginRequired": true, "flowLayout": false })
            ])
        ]),
        createRoute_1("result", [
            createRoute_1(":resultId", [
                createRoute_1("image", lazy_1(() => import("../views/result/{resultId}/image.js")), { "route": "image", "menu": { "order": 2, "icon": "line-awesome/svg/file.svg", "exclude": true }, "title": "Image Upload", "loginRequired": true, "flowLayout": false }),
                createRoute_1("result", lazy_1(() => import("../views/result/{resultId}/result.js")), { "route": "result", "menu": { "order": 2, "icon": "line-awesome/svg/file.svg", "exclude": true }, "title": "Result", "loginRequired": true, "flowLayout": false })
            ])
        ]),
        createRoute_1("sample", lazy_1(() => import("../views/sample.js")), { "route": "sample", "menu": { "order": 3, "icon": "line-awesome/svg/folder-open.svg" }, "title": "Sample", "loginRequired": true, "flowLayout": false }),
        createRoute_1("system", [
            createRoute_1("", lazy_1(() => import("../views/system/@index.js")), { "route": "", "menu": { "order": 1, "icon": "line-awesome/svg/suitcase-rolling-solid.svg" }, "title": "System", "loginRequired": true, "flowLayout": false }),
            createRoute_1("setting", lazy_1(() => import("../views/system/setting.js")), { "route": "setting", "menu": { "order": 1, "icon": "line-awesome/svg/suitcase-rolling-solid.svg", "exclude": true }, "title": "Setting", "loginRequired": true, "flowLayout": false })
        ]),
        createRoute_1("test", [
            createRoute_1("", lazy_1(() => import("../views/test/@index.js")), { "route": "", "menu": { "order": 4, "icon": "line-awesome/svg/suitcase-rolling-solid.svg" }, "title": "Test", "loginRequired": true, "flowLayout": false }),
            createRoute_1("cyclic", lazy_1(() => import("../views/test/cyclic.js")), { "route": "cyclic", "menu": { "order": 1, "icon": "line-awesome/svg/test-tube.svg", "exclude": true }, "title": "Cyclic Test", "loginRequired": true, "flowLayout": false }),
            createRoute_1("destructive", lazy_1(() => import("../views/test/destructive.js")), { "route": "destructive", "menu": { "order": 1, "icon": "line-awesome/svg/test-tube.svg", "exclude": true }, "title": "Destructive Test", "loginRequired": true, "flowLayout": false }),
            createRoute_1("timeCyclic", lazy_1(() => import("../views/test/timeCyclic.js")), { "route": "timeCyclic", "menu": { "order": 1, "icon": "line-awesome/svg/test-tube.svg", "exclude": true }, "title": "Time Cyclic Test", "loginRequired": true, "flowLayout": false })
        ])
    ])
];
export default routes;
