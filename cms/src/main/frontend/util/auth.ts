import { configureAuth } from '@vaadin/hilla-react-auth';
import { UserEndpoint } from 'cms/generated/endpoints';

const auth = configureAuth(UserEndpoint.getAuthenticatedUser);

export const useAuth = auth.useAuth;
export const AuthProvider = auth.AuthProvider;
