/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.agentservicesaccount.auth

import com.google.inject.{ImplementedBy, Singleton}
import javax.inject.Inject
import play.api.mvc.Results._
import play.api.mvc.{Request, Result}
import play.api.{Configuration, Environment, Logging, Mode}
import uk.gov.hmrc.agentservicesaccount.config.AppConfig
import uk.gov.hmrc.auth.otac.{Authorised, OtacAuthConnector}
import uk.gov.hmrc.http.{HeaderCarrier, SessionKeys}

import scala.concurrent.{ExecutionContext, Future}

class PasscodeVerificationException(msg: String) extends RuntimeException(msg)

@ImplementedBy(classOf[FrontendPasscodeVerification])
trait PasscodeVerification extends Logging {
  def apply[A](body: Boolean => Future[Result])(implicit request: Request[A], headerCarrier: HeaderCarrier, ec: ExecutionContext): Future[Result]
}

@Singleton
class FrontendPasscodeVerification @Inject()(configuration: Configuration,
                                             environment: Environment,
                                             otacAuthConnector: OtacAuthConnector,
                                            appConfig: AppConfig)
  extends PasscodeVerification {

  val tokenParam = "p"

  lazy val passcodeEnabled: Boolean = appConfig.passcodeAuthEnabled
  lazy val passcodeRegime: String = appConfig.passcodeAuthRegime

  lazy val env: String = if (environment.mode.equals(Mode.Test)) Mode.Test.toString else appConfig.runMode.getOrElse(Mode.Dev.toString)
  lazy val verificationURL: String = configuration.getOptional[String](s"govuk-tax.$env.url.verification-frontend.redirect").getOrElse("/verification")
  lazy val logoutUrl = s"$verificationURL/otac/logout/$passcodeRegime"

  private def redirectToLoginWithToken[A](implicit request: Request[A]): Option[Result] = {
    request.getQueryString(tokenParam).map { nonUrlEncodedToken =>
      val redirectUrl = CallOps.addParamsToUrl(s"$verificationURL/otac/login", tokenParam -> Some(nonUrlEncodedToken))

      addRedirectUrl(nonUrlEncodedToken)(request)(Redirect(redirectUrl))
    }
  }

  private def addRedirectUrl[A](token: String)(implicit request: Request[A]): Result => Result = e =>
    e.addingToSession(SessionKeys.redirect -> buildRedirectUrl(request))
      .addingToSession("otacTokenParam" -> token)

  private def buildRedirectUrl[A](req: Request[A]): String =
    if (env != "Prod") s"http${if (req.secure) "s" else ""}://${req.host}${req.path}" else req.path

  def apply[A](body: Boolean => Future[Result])(implicit request: Request[A], headerCarrier: HeaderCarrier, ec: ExecutionContext): Future[Result] = {
    if (passcodeEnabled) {
      request.session.get(SessionKeys.otacToken) match {
        case Some(otacToken) =>
          otacAuthConnector.authorise(passcodeRegime, headerCarrier, Option(otacToken)).flatMap {
            case Authorised => body(true)
            case _ => body(false)
          }.recoverWith {
            case ex =>
              logger.warn("error during PassCodeVerification: IRV option may not be visible to the agents", ex)
              body(false)
          }
        case None =>
          redirectToLoginWithToken.map(Future.successful).getOrElse(body(false))
      }
    } else {
      body(true)
    }
  }
}
