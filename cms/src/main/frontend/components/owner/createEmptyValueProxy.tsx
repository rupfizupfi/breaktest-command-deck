import User from "Frontend/generated/ch/rupfizupfi/deck/data/User";
import {UserEndpoint} from "Frontend/generated/endpoints";
import AbstractEntityModel from "Frontend/generated/ch/rupfizupfi/deck/data/AbstractEntityModel";
import {DetachedModelConstructor} from "@vaadin/hilla-lit-form";
import {peekableCacheUntilRejected} from "cms/util/peekableCache";
import {withDefaultOwner} from "cms/components/owner/defaultOwner";

/** The signed-in user, fetched once per page; a failed fetch is retried on the next call. */
export const currentUser = peekableCacheUntilRejected<User | undefined>(() => UserEndpoint.getAuthenticatedUser());

export const getCurrentUser: () => Promise<User | undefined> = currentUser.get;

/**
 * Makes a new entity of this model default its owner to the signed-in user (the OwnerSelector can
 * still set "all users" deliberately). The form that creates the entity must be rendered inside
 * {@code CurrentUserGate}: Hilla's form takes its empty value once, at mount, so a form that
 * mounted before the user was known would create every new row shared — see {@link withDefaultOwner}.
 */
export default function createEmptyValueProxy<T extends AbstractEntityModel>(modelDefinition:DetachedModelConstructor<T>):void {
    // Start the lookup now, so the gate usually finds it done. The rejection is handled here; the
    // gate retries it.
    currentUser.get().catch(() => undefined);

    // @ts-ignore
    modelDefinition.createEmptyValue = withDefaultOwner(modelDefinition.createEmptyValue, currentUser);
}
