import User from "Frontend/generated/ch/rupfizupfi/deck/data/User";
import {UserEndpoint} from "Frontend/generated/endpoints";
import AbstractEntityModel from "Frontend/generated/ch/rupfizupfi/deck/data/AbstractEntityModel";
import {DetachedModelConstructor} from "@vaadin/hilla-lit-form";

let currentUser:Promise<User | undefined> | undefined;

export function getCurrentUser():Promise<User | undefined> {
    if (!currentUser) {
        currentUser = UserEndpoint.getAuthenticatedUser();
    }
    return currentUser;
}

export default function createEmptyValueProxy<T extends AbstractEntityModel>(modelDefinition:DetachedModelConstructor<T>):void {
    getCurrentUser().then(user => {
        // @ts-ignore
        const old = modelDefinition.createEmptyValue;
        // @ts-ignore
        modelDefinition.createEmptyValue = function() {
            const entity = old();
            entity.owner = user;
            return entity;
        }
    });
}
