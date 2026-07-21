declare namespace Api {
  /**
   * namespace Auth
   *
   * backend api module: "auth"
   */
  namespace Auth {
    interface LoginToken {
      token: string;
      refreshToken: string;
    }

    interface UserInfo {
      userId: string;
      userName: string;
      displayName: string;
      passwordChangeRequired: boolean;
      roles: string[];
      buttons: string[];
    }
  }
}
