/**
 * The deck menu, the server-side route check and protect() all read the merged tree, so metadata
 * only cms declares (rolesAllowed on views/system/setting.tsx) must survive the merge.
 */
import { describe, expect, it } from 'vitest';
import { mergeRoutes, type ViewConfigRoute } from '../../../routeMerge.js';

const layout = (children: ViewConfigRoute[], extra: Record<string, unknown> = {}): ViewConfigRoute[] =>
  [{route: '', title: 'Main Layout', params: {}, ...extra, children}];

describe('mergeRoutes', () => {
  it('appends a route only cms defines, with all its metadata', () => {
    const {routes, conflicts} = mergeRoutes(
      layout([{route: 'run', title: 'Execute test'}]),
      layout([{route: 'customer', title: 'Customer', rolesAllowed: ['ADMIN']}]));

    expect(routes[0].children!.map(r => r.route)).toEqual(['run', 'customer']);
    expect(routes[0].children![1].rolesAllowed).toEqual(['ADMIN']);
    expect(conflicts).toEqual([]);
  });

  it('keeps cms metadata on a route both define when the deck copy lacks it', () => {
    const {routes, conflicts} = mergeRoutes(
      layout([{route: 'system', params: {}, children: [
        {route: 'faults', rolesAllowed: ['ADMIN']},
        {route: 'setting', title: 'Setting', menu: {exclude: true}},
      ]}]),
      layout([{route: 'system', params: {}, children: [
        {route: 'setting', title: 'Setting', menu: {exclude: true, order: 1}, rolesAllowed: ['ADMIN']},
      ]}]));

    const setting = routes[0].children![0].children!.find(r => r.route === 'setting')!;
    expect(setting.rolesAllowed).toEqual(['ADMIN']);
    expect(setting.menu).toEqual({exclude: true, order: 1});
    expect(conflicts).toEqual([]);
  });

  it("merges a parent route's own metadata, not just its children", () => {
    const {routes} = mergeRoutes(
      layout([], {title: 'Main Layout'}),
      layout([], {title: 'Main Layout', loginRequired: true, menu: {order: 3}}));

    expect(routes[0]).toMatchObject({title: 'Main Layout', loginRequired: true, menu: {order: 3}});
  });

  it('lets the deck win an explicit disagreement and reports it', () => {
    const {routes, conflicts} = mergeRoutes(
      layout([{route: 'result', children: [{route: 'image', title: 'Deck image', menu: {order: 2}}]}]),
      layout([{route: 'result', children: [{route: 'image', title: 'Image Upload', menu: {order: 5}}]}]));

    expect(routes[0].children![0].children![0]).toMatchObject({title: 'Deck image', menu: {order: 2}});
    expect(conflicts).toEqual([
      {path: '/result/image', field: 'title', deck: 'Deck image', cms: 'Image Upload'},
      {path: '/result/image', field: 'menu.order', deck: 2, cms: 5},
    ]);
  });

  it('compares arrays as values, so an equal rolesAllowed is no conflict', () => {
    const {conflicts} = mergeRoutes(
      layout([{route: 'admin', rolesAllowed: ['ADMIN']}]),
      layout([{route: 'admin', rolesAllowed: ['ADMIN']}]));

    expect(conflicts).toEqual([]);
  });

  it('leaves both inputs unmodified', () => {
    const deck = layout([{route: 'run'}]);
    const cms = layout([{route: 'customer', menu: {order: 1}}]);
    const before = JSON.stringify([deck, cms]);

    const {routes} = mergeRoutes(deck, cms);
    (routes[0].children![1].menu as {order: number}).order = 99;

    expect(JSON.stringify([deck, cms])).toBe(before);
  });
});
